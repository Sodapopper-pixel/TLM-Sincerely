package com.github.tartaricacid.tlm_sincerely.priority;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkInternalSetTaskGuard;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPreset;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPresetService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkState;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkStateService;
import com.github.tartaricacid.tlm_sincerely.priority.decision.MaidSwitchState;
import com.github.tartaricacid.tlm_sincerely.priority.decision.TaskSwitchDecisionEngine;
import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import com.github.tartaricacid.tlm_sincerely.priority.detection.MaidDetectionCache;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskDetectionScheduler;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Server tick driver for the auto work switch (T-2 B1).
 *
 * <p>Filter contract (per the v2 plan):
 * <ol>
 *   <li>Only maids whose {@link Activity} is {@code WORK} are considered.</li>
 *   <li>Only maids with {@link AutoWorkState#enabled()} {@code = true} are
 *       actually driven through the scheduler / decision engine.</li>
 *   <li>The global {@link PriorityConfig#ENABLED} switch only pauses
 *       scheduling; it never calls {@code setTask}, never clears
 *       {@link AutoWorkState} and never touches the real task.</li>
 *   <li>If {@link AutoWorkStateService} is not bound (dedicated server
 *       race, mid-unload, etc.) the handler returns early and does
 *       nothing — the conservative safe default.</li>
 * </ol>
 *
 * <p>The handler no longer reads {@link TaskPriorityManager}; legacy
 * preset data remains only for the GUI and AI Tool and is not part of
 * the decision path.
 */
@Mod.EventBusSubscriber(modid = SincerelyExtension.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TaskAutoSwitchHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskAutoSwitchHandler.class);
    private static final int STUCK_BRAIN_REFRESH_TICKS = 60;
    private static final int STARTUP_TRACE_TICKS = 120;
    private static final TaskSwitchDecisionEngine DECISION_ENGINE = new TaskSwitchDecisionEngine();

    private TaskAutoSwitchHandler() {
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        TaskDetectionRuntimeState.start(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TaskDetectionRuntimeState.stop(event.getServer());
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof EntityMaid maid && event.getLevel() instanceof ServerLevel level) {
            TaskDetectionRuntimeState.clearMaid(level.getServer(), maid.getUUID());
        }
    }

    @SubscribeEvent
    public static void onMaidDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof EntityMaid maid && maid.level() instanceof ServerLevel level) {
            TaskDetectionRuntimeState.clearMaid(level.getServer(), maid.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        // Always clear any stale internal-setTask guard at the start of a
        // tick. If a previous call forgot to clear it (e.g. an exception
        // slipped past the finally block), this prevents the flag from
        // bleeding into unrelated work on the same thread.
        AutoWorkInternalSetTaskGuard.clearIfStale("TaskAutoSwitchHandler#onServerTick");

        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // Global switch off: pause scheduling. Do NOT call setTask, do
        // NOT touch AutoWorkState, do NOT touch the real task. The
        // detection caches and switch state remain so re-enabling picks
        // up where we left off.
        if (!PriorityConfig.ENABLED.get()) {
            return;
        }

        MinecraftServer server = event.getServer();
        AutoWorkStateService stateService = AutoWorkStateService.getOrNull(server);
        AutoWorkPresetService presetService = AutoWorkPresetService.getOrNull(server);
        // Conservative: if either service is not bound, skip this tick.
        // This covers dedicated-server reload races, late ticks after
        // server stop, and any future lifecycle reorderings.
        if (stateService == null || presetService == null) {
            return;
        }

        long currentTick = server.getTickCount();

        // First pass: collect working maids so we can build the runtime
        // snapshot and only then filter by AutoWorkState (the state
        // lookup must happen on the server thread, which is true here).
        List<EntityMaid> workingMaids = findWorkingMaids(server);
        Set<UUID> loadedMaidIds = new HashSet<>();
        for (EntityMaid maid : workingMaids) {
            loadedMaidIds.add(maid.getUUID());
        }

        TaskDetectionRuntimeState runtime = TaskDetectionRuntimeState.get(server);
        runtime.beginTick(currentTick, loadedMaidIds);

        // Second pass: build per-maid preset jobs and run the scheduler.
        // A maid without a usable preset is silently skipped (its
        // AutoWorkState.enabled is still respected — the filter happens
        // here).
        List<TaskDetectionScheduler.MaidPresetJob> schedulerJobs = new ArrayList<>();
        List<AutoWorkMaid> autoMaids = new ArrayList<>();
        for (EntityMaid maid : workingMaids) {
            AutoWorkState state = stateService.getState(maid);
            if (!state.enabled()) {
                continue;
            }
            AutoWorkPreset preset = presetService.resolveForMaid(state);
            if (preset == null) {
                // No preset library is available for this maid; skip
                // without touching the real task. This is intentionally
                // distinct from "preset exists but is empty" — the
                // latter still goes through detection so a future add
                // is observed.
                LOGGER.debug("[TaskAutoSwitch] maid={} has no resolvable preset; skipping",
                        maid.getUUID());
                continue;
            }
            schedulerJobs.add(new TaskDetectionScheduler.MaidPresetJob(maid, preset));
            autoMaids.add(new AutoWorkMaid(maid, preset));
        }
        runtime.scheduler().tick(runtime, schedulerJobs, currentTick);

        // Third pass: drive the decision engine per maid with its
        // resolved preset. The preset's list order is the authoritative
        // priority; there is no global active preset any more.
        boolean decisionTick = currentTick % PriorityConfig.COOLDOWN.get() == 0;
        for (AutoWorkMaid am : autoMaids) {
            EntityMaid maid = am.maid();
            AutoWorkPreset preset = am.preset();
            List<ResourceLocation> sortedTasks = preset.getOrder();
            // An empty preset is a legal configuration: the maid keeps
            // whatever real task it has and we never touch it. This
            // makes it safe for a user to set the order list to "no
            // tasks" and observe no switching, instead of being
            // yanked back to default.
            if (sortedTasks.isEmpty()) {
                continue;
            }
            MaidDetectionCache cache = runtime.getDetectionCache(maid);
            MaidSwitchState state = runtime.getSwitchState(maid);
            boolean attackHandled = DECISION_ENGINE.handleExperimentalAttackPreempt(maid, preset, sortedTasks,
                    cache, state, currentTick);
            if (!attackHandled && (decisionTick || cache.hasDefinitiveUpdateAt(currentTick))) {
                DECISION_ENGINE.evaluateNormalSwitch(maid, preset, sortedTasks, cache, state, currentTick);
            }
            traceTaskStartup(maid, cache, state, currentTick);
        }
    }

    private static void traceTaskStartup(EntityMaid maid, MaidDetectionCache cache,
                                         MaidSwitchState state, long currentTick) {
        long switchTick = state.lastSwitchTick();
        if (switchTick == Long.MIN_VALUE || currentTick < switchTick) {
            return;
        }
        long age = currentTick - switchTick;
        if (age > STARTUP_TRACE_TICKS) {
            return;
        }

        var brain = maid.getBrain();
        int mask = 0;
        if (brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT)) mask |= 1;
        if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_PRESENT)) mask |= 2;
        if (brain.checkMemory(MemoryModuleType.PATH, MemoryStatus.VALUE_PRESENT)) mask |= 4;
        if (brain.checkMemory(InitEntities.TARGET_POS.get(), MemoryStatus.VALUE_PRESENT)) mask |= 8;

        boolean firstBusy = mask != 0 && !state.startupBusyObserved();
        boolean changed = mask != state.startupMemoryMask();
        if (firstBusy) {
            state.markStartupBusyObserved();
        }
        if (changed || firstBusy || age == 0 || age % 20 == 0 || age == STARTUP_TRACE_TICKS) {
            LOGGER.debug("[TaskStartup] tick={} age={} maid={} task={} firstBusy={} brain=[ATK:{} WLK:{} PATH:{} TGT:{}]",
                    currentTick, age, maid.getUUID(), maid.getTask().getUid(), firstBusy,
                    (mask & 1) != 0 ? 1 : 0,
                    (mask & 2) != 0 ? 1 : 0,
                    (mask & 4) != 0 ? 1 : 0,
                    (mask & 8) != 0 ? 1 : 0);
            state.setStartupMemoryMask(mask);
        }

        if (PriorityConfig.FORCE_BRAIN_REFRESH_ON_STUCK.get()
                && !state.forcedBrainRefreshDone()
                && age >= STUCK_BRAIN_REFRESH_TICKS
                && mask == 0
                && cache.getFresh(maid.getTask().getUid(), currentTick).availability() == Availability.AVAILABLE
                && maid.level() instanceof ServerLevel level) {
            maid.refreshBrain(level);
            state.markForcedBrainRefreshDone();
            LOGGER.debug("[TaskStartup] tick={} age={} maid={} task={} action=FORCE_BRAIN_REFRESH",
                    currentTick, age, maid.getUUID(), maid.getTask().getUid());
        }
    }

    private static List<EntityMaid> findWorkingMaids(MinecraftServer server) {
        List<EntityMaid> maids = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (entity instanceof EntityMaid maid && maid.isAlive()
                        && maid.getScheduleDetail() == Activity.WORK) {
                    maids.add(maid);
                }
            }
        }
        return maids;
    }

    /**
     * Internal pairing of a maid with its resolved per-tick preset.
     * Distinct from {@link TaskDetectionScheduler.MaidPresetJob} so the
     * decision pass can iterate its own list (the scheduler also keeps
     * its own copy for the detection pass).
     */
    private record AutoWorkMaid(EntityMaid maid, AutoWorkPreset preset) {
    }
}
