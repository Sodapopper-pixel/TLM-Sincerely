package com.github.tartaricacid.tlm_sincerely.priority;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
import com.github.tartaricacid.tlm_sincerely.priority.decision.MaidSwitchState;
import com.github.tartaricacid.tlm_sincerely.priority.decision.TaskSwitchDecisionEngine;
import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import com.github.tartaricacid.tlm_sincerely.priority.detection.MaidDetectionCache;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import net.minecraft.resources.ResourceLocation;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
        if (event.phase != TickEvent.Phase.END || !PriorityConfig.ENABLED.get()) {
            return;
        }

        TaskPriorityPreset preset = TaskPriorityManager.getActivePreset();
        if (preset == null || preset.getSortedTasks().isEmpty()) {
            return;
        }
        long currentTick = event.getServer().getTickCount();
        List<ResourceLocation> sortedTasks = sortedTasks(preset);
        List<EntityMaid> maids = findWorkingMaids(event);
        Set<UUID> loadedMaidIds = new HashSet<>();
        for (EntityMaid maid : maids) {
            loadedMaidIds.add(maid.getUUID());
        }

        TaskDetectionRuntimeState runtime = TaskDetectionRuntimeState.get(event.getServer());
        runtime.beginTick(currentTick, loadedMaidIds);
        runtime.scheduler().tick(runtime, maids, sortedTasks, currentTick);

        boolean decisionTick = currentTick % PriorityConfig.COOLDOWN.get() == 0;
        for (EntityMaid maid : maids) {
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

    private static List<ResourceLocation> sortedTasks(TaskPriorityPreset preset) {
        List<ResourceLocation> taskIds = new ArrayList<>(preset.getSortedTasks());
        taskIds.sort(Comparator.comparingInt(taskUid -> preset.getPriorities().getOrDefault(taskUid, 10)));
        return taskIds;
    }

    private static List<EntityMaid> findWorkingMaids(TickEvent.ServerTickEvent event) {
        List<EntityMaid> maids = new ArrayList<>();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (entity instanceof EntityMaid maid && maid.isAlive()
                        && maid.getScheduleDetail() == Activity.WORK) {
                    maids.add(maid);
                }
            }
        }
        return maids;
    }
}
