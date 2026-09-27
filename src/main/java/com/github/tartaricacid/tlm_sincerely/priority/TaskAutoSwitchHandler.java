package com.github.tartaricacid.tlm_sincerely.priority;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkInternalSetTaskGuard;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkState;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkStateService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkTaskDataKeys;
import com.github.tartaricacid.tlm_sincerely.priority.decision.MaidSwitchState;
import com.github.tartaricacid.tlm_sincerely.priority.decision.TaskSwitchDecisionEngine;
import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionResult;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server tick driver for the auto work switch.
 *
 * <p>Filter contract:
 * <ol>
 *   <li>Only maids whose {@link Activity} is {@code WORK} are considered.</li>
 *   <li>Only maids with {@link AutoWorkState#enabled()} {@code = true} are
 *       driven through the scheduler / decision engine.</li>
 *   <li>The global {@link PriorityConfig#ENABLED} switch only pauses
 *       scheduling; it never calls {@code setTask}, never clears
 *       {@link AutoWorkState} and never touches the real task.</li>
 *   <li>Scheduling reads only the maid's bound snapshot order — the preset
 *       library is private to each client and is never consulted here.</li>
 * </ol>
 */
@EventBusSubscriber(modid = SincerelyExtension.MOD_ID)
public final class TaskAutoSwitchHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskAutoSwitchHandler.class);
    private static final int STUCK_BRAIN_REFRESH_TICKS = 60;
    private static final int STARTUP_TRACE_TICKS = 120;
    private static final TaskSwitchDecisionEngine DECISION_ENGINE = new TaskSwitchDecisionEngine();
    /** Server-thread requests issued when a player/agent enables or retargets auto work. */
    private static final Map<UUID, ImmediateEvaluationRequest> IMMEDIATE_EVALUATION_REQUESTS = new HashMap<>();
    /** Maids already restored after the current server instance rebound. */
    private static final Set<UUID> SERVER_REBIND_RESTORED = new HashSet<>();
    /** Cached UUIDs of WORK maids for the current server, maintained via lifecycle events. */
    private static final Map<MinecraftServer, Set<UUID>> WORKING_MAIDS = new IdentityHashMap<>();
    private static final int RECONCILIATION_INTERVAL = 40;

    private TaskAutoSwitchHandler() {
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        IMMEDIATE_EVALUATION_REQUESTS.clear();
        SERVER_REBIND_RESTORED.clear();
        TaskDetectionRuntimeState.start(event.getServer());
        WORKING_MAIDS.put(event.getServer(), new HashSet<>());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        IMMEDIATE_EVALUATION_REQUESTS.clear();
        SERVER_REBIND_RESTORED.clear();
        TaskDetectionRuntimeState.stop(event.getServer());
        WORKING_MAIDS.remove(event.getServer());
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof EntityMaid maid && event.getLevel() instanceof ServerLevel level) {
            if (maid.getScheduleDetail() == Activity.WORK) {
                WORKING_MAIDS.computeIfAbsent(level.getServer(), s -> new HashSet<>()).add(maid.getUUID());
            }
            // Legacy data has only a preset id: bake the bound snapshot from
            // the frozen seed as soon as the maid is loaded.
            AutoWorkStateService stateService = AutoWorkStateService.getOrNull(level.getServer());
            if (stateService != null && maid.getData(AutoWorkTaskDataKeys.STATE_KEY) != null) {
                stateService.ensureBoundSnapshot(maid);
            }
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof EntityMaid maid && event.getLevel() instanceof ServerLevel level) {
            IMMEDIATE_EVALUATION_REQUESTS.remove(maid.getUUID());
            TaskDetectionRuntimeState.clearMaid(level.getServer(), maid.getUUID());
            Set<UUID> cached = WORKING_MAIDS.get(level.getServer());
            if (cached != null) {
                cached.remove(maid.getUUID());
            }
        }
    }

    @SubscribeEvent
    public static void onMaidDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof EntityMaid maid && maid.level() instanceof ServerLevel level) {
            IMMEDIATE_EVALUATION_REQUESTS.remove(maid.getUUID());
            TaskDetectionRuntimeState.clearMaid(level.getServer(), maid.getUUID());
            Set<UUID> cached = WORKING_MAIDS.get(level.getServer());
            if (cached != null) {
                cached.remove(maid.getUUID());
            }
        }
    }

    /**
     * Schedules one evaluation on the next server tick. It bypasses only the
     * poll-interval wait; normal detector availability and task-hold safety
     * rules remain authoritative.
     */
    public static void requestImmediateEvaluation(EntityMaid maid, String reason) {
        requestImmediateEvaluation(maid, reason, false);
    }

    /**
     * Requests an immediate decision and, when requested, invalidates every
     * cached result before the next scheduler pass creates fresh evidence.
     */
    public static void requestImmediateEvaluation(EntityMaid maid, String reason, boolean forceRescan) {
        if (maid == null || !(maid.level() instanceof ServerLevel level)) {
            return;
        }
        TaskDetectionRuntimeState runtime = TaskDetectionRuntimeState.get(level.getServer());
        long generation = forceRescan
                ? runtime.beginForceRescan(maid)
                : runtime.getDetectionGeneration(maid);
        ImmediateEvaluationRequest previous = IMMEDIATE_EVALUATION_REQUESTS.get(maid.getUUID());
        if (previous != null && previous.forceRescan() && !forceRescan) {
            generation = previous.generation();
            forceRescan = true;
        }
        ImmediateEvaluationRequest request = new ImmediateEvaluationRequest(
                reason == null ? "UNSPECIFIED" : reason, generation, forceRescan);
        IMMEDIATE_EVALUATION_REQUESTS.put(maid.getUUID(), request);
        LOGGER.debug("[TaskAutoSwitch] immediate evaluation requested maid={} generation={} forceRescan={} reason={}",
                maid.getUUID(), generation, forceRescan, request.reason());
    }

    /** Restores scheduler evidence after a server instance creates fresh runtime state. */
    public static int requestServerRebindRescans(MinecraftServer server) {
        AutoWorkStateService stateService = AutoWorkStateService.getOrNull(server);
        if (stateService == null) {
            return 0;
        }
        int restored = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                restored += restoreMaidIfNeeded(maidOrNull(entity), stateService);
            }
        }
        LOGGER.info("[TaskState] SERVER_REBIND restoredMaids={}", restored);
        return restored;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // Always clear any stale internal-setTask guard at the start of a
        // tick. If a previous call forgot to clear it (e.g. an exception
        // slipped past the finally block), this prevents the flag from
        // bleeding into unrelated work on the same thread.
        AutoWorkInternalSetTaskGuard.clearIfStale("TaskAutoSwitchHandler#onServerTick");

        // Global switch off: pause scheduling. Do NOT call setTask, do
        // NOT touch AutoWorkState, do NOT touch the real task. The
        // detection caches and switch state remain so re-enabling picks
        // up where we left off.
        if (!PriorityConfig.ENABLED.get()) {
            return;
        }

        MinecraftServer server = event.getServer();
        AutoWorkStateService stateService = AutoWorkStateService.getOrNull(server);
        // Conservative: if the service is not bound, skip this tick.
        if (stateService == null) {
            return;
        }

        long currentTick = server.getTickCount();

        // Periodic reconciliation so Activity changes and edge-case joins
        // do not leave the cache stale between lifecycle events.
        if (currentTick % RECONCILIATION_INTERVAL == 0) {
            reconcileWorkingMaids(server);
        }

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

        // Second pass: build per-maid orders from the bound snapshots and run
        // the scheduler. A maid without a baked order is silently skipped.
        List<TaskDetectionScheduler.MaidPresetJob> schedulerJobs = new ArrayList<>();
        List<AutoWorkMaid> autoMaids = new ArrayList<>();
        for (EntityMaid maid : workingMaids) {
            AutoWorkState state = stateService.getState(maid);
            if (!state.enabled()) {
                continue;
            }
            AutoWorkState bound = stateService.ensureBoundSnapshot(maid);
            List<ResourceLocation> order = bound.order();
            if (order.isEmpty()) {
                // An empty bound order is a legal configuration: the maid
                // keeps whatever real task it has and we never touch it.
                LOGGER.debug("[TaskAutoSwitch] maid={} has an empty bound order; skipping", maid.getUUID());
                continue;
            }
            restoreMaidIfNeeded(maid, stateService);
            schedulerJobs.add(new TaskDetectionScheduler.MaidPresetJob(maid, order));
            autoMaids.add(new AutoWorkMaid(maid, bound.presetId(), order));
        }
        TaskDetectionScheduler.SchedulerStats budgetStats = runtime.scheduler().tick(runtime, schedulerJobs, currentTick);
        LOGGER.debug("[TaskBudget] tick={} enabledMaids={} dueJobs={}/{} block={}/{} path={}/{} "
                        + "blockExhausted={} pathExhausted={} forceRescanMaids={} noDetectorSkipped={} policySkipped={}",
                currentTick, autoMaids.size(), budgetStats.dueJobCount(), budgetStats.jobCount(),
                budgetStats.blockUsed(), budgetStats.blockCap(), budgetStats.pathUsed(), budgetStats.pathCap(),
                budgetStats.blockBudgetExhausted(), budgetStats.pathBudgetExhausted(),
                budgetStats.forceRescanMaidCount(), budgetStats.skippedNoDetector(), budgetStats.skippedPolicy());

        // Third pass: drive the decision engine per maid with its bound
        // order. The order list is the authoritative priority; there is no
        // global active preset any more.
        boolean decisionTick = currentTick % PriorityConfig.COOLDOWN.get() == 0;
        for (AutoWorkMaid am : autoMaids) {
            EntityMaid maid = am.maid();
            List<ResourceLocation> sortedTasks = am.order();
            MaidDetectionCache cache = runtime.getDetectionCache(maid);
            MaidSwitchState state = runtime.getSwitchState(maid);
            long generation = runtime.getDetectionGeneration(maid);
            ImmediateEvaluationRequest immediateRequest = IMMEDIATE_EVALUATION_REQUESTS.remove(maid.getUUID());
            observeMaidBusy(maid, cache, state, currentTick, generation);
            boolean attackHandled = DECISION_ENGINE.handleExperimentalAttackPreempt(maid, sortedTasks,
                    cache, state, currentTick, generation);
            if (!attackHandled && (immediateRequest != null || decisionTick
                    || cache.hasDefinitiveUpdateAt(generation, currentTick))) {
                DECISION_ENGINE.evaluateNormalSwitch(maid, sortedTasks, cache, state, currentTick, generation);
                if (immediateRequest != null) {
                    LOGGER.debug("[TaskAutoSwitch] immediate evaluation completed maid={} preset={} generation={} "
                                    + "reason={} freshTaskCount={} staleIgnoredCount={} task={}",
                            maid.getUUID(), am.presetId(), generation, immediateRequest.reason(),
                            cache.freshResultCount(generation, currentTick), cache.staleIgnoredCount(),
                            maid.getTask().getUid());
                }
            }
            traceTaskStartup(maid, cache, state, currentTick, generation);
        }
    }

    /**
     * Per-tick busy observation for the busy guard: records when the maid's
     * brain shows active work for the current task (ATTACK_TARGET /
     * WALK_TARGET / PATH / TARGET_POS) and how long the current task has been
     * continuously UNAVAILABLE. Runs for every auto-work maid on every tick
     * (not only decision ticks) so the guard always has fresh evidence.
     * Observation runs before the current tick's decision, including for a
     * maid that has never been switched by auto work. The current busy period
     * is anchored by {@link MaidSwitchState#busyStartTick()} and remains
     * bounded by the configured hard ceiling.
     */
    private static void observeMaidBusy(EntityMaid maid, MaidDetectionCache cache, MaidSwitchState state,
                                        long currentTick, long generation) {
        var brain = maid.getBrain();
        if (brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT)
                || brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_PRESENT)
                || brain.checkMemory(MemoryModuleType.PATH, MemoryStatus.VALUE_PRESENT)
                || brain.checkMemory(InitEntities.TARGET_POS.get(), MemoryStatus.VALUE_PRESENT)) {
            state.markBusyObserved(currentTick, PriorityConfig.BUSY_IDLE_FORGIVE_TICKS.get());
        }
        ResourceLocation currentUid = maid.getTask().getUid();
        DetectionResult currentResult = cache.getFresh(currentUid, generation, currentTick);
        state.trackCurrentAvailability(currentUid, currentResult.availability(), currentTick);
    }

    private static void traceTaskStartup(EntityMaid maid, MaidDetectionCache cache,
                                          MaidSwitchState state, long currentTick, long generation) {
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
            LOGGER.debug("[TaskStartup] tick={} age={} maid={} generation={} task={} firstBusy={} brain=[ATK:{} WLK:{} PATH:{} TGT:{}]",
                    currentTick, age, maid.getUUID(), generation, maid.getTask().getUid(), firstBusy,
                    (mask & 1) != 0 ? 1 : 0,
                    (mask & 2) != 0 ? 1 : 0,
                    (mask & 4) != 0 ? 1 : 0,
                    (mask & 8) != 0 ? 1 : 0);
            state.setStartupMemoryMask(mask);
        }

        DetectionResult currentResult = cache.getFresh(maid.getTask().getUid(), generation, currentTick);
        if (PriorityConfig.FORCE_BRAIN_REFRESH_ON_STUCK.get()
                && !state.forcedBrainRefreshDone()
                && age >= STUCK_BRAIN_REFRESH_TICKS
                && mask == 0
                && currentResult.availability() == Availability.AVAILABLE
                && (currentResult.targetPos() != null || currentResult.targetEntityUuid() != null)
                && maid.level() instanceof ServerLevel level) {
            maid.refreshBrain(level);
            state.markForcedBrainRefreshDone();
            LOGGER.debug("[TaskStartup] tick={} age={} maid={} generation={} task={} action=FORCE_BRAIN_REFRESH",
                    currentTick, age, maid.getUUID(), generation, maid.getTask().getUid());
        }
    }

    private static List<EntityMaid> findWorkingMaids(MinecraftServer server) {
        Set<UUID> cached = WORKING_MAIDS.get(server);
        if (cached == null) {
            // Cold start / missing cache: rebuild once. An existing empty set
            // is authoritative and must not trigger a full entity scan every tick.
            List<EntityMaid> maids = new ArrayList<>();
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getEntities().getAll()) {
                    if (entity instanceof EntityMaid maid && maid.isAlive()
                            && maid.getScheduleDetail() == Activity.WORK) {
                        maids.add(maid);
                    }
                }
            }
            WORKING_MAIDS.put(server, maids.stream()
                    .map(EntityMaid::getUUID)
                    .collect(java.util.stream.Collectors.toCollection(HashSet::new)));
            return maids;
        }
        if (cached.isEmpty()) {
            return List.of();
        }
        List<EntityMaid> maids = new ArrayList<>();
        List<UUID> stale = new ArrayList<>();
        for (UUID uuid : cached) {
            boolean found = false;
            for (ServerLevel level : server.getAllLevels()) {
                Entity entity = level.getEntity(uuid);
                if (entity instanceof EntityMaid maid && maid.isAlive()
                        && maid.getScheduleDetail() == Activity.WORK) {
                    maids.add(maid);
                    found = true;
                    break;
                }
            }
            if (!found) {
                stale.add(uuid);
            }
        }
        if (!stale.isEmpty()) {
            cached.removeAll(stale);
        }
        return maids;
    }

    private static void reconcileWorkingMaids(MinecraftServer server) {
        Set<UUID> reconciled = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (entity instanceof EntityMaid maid && maid.isAlive()
                        && maid.getScheduleDetail() == Activity.WORK) {
                    reconciled.add(maid.getUUID());
                }
            }
        }
        WORKING_MAIDS.put(server, reconciled);
    }

    private static EntityMaid maidOrNull(Entity entity) {
        return entity instanceof EntityMaid maid ? maid : null;
    }

    private static int restoreMaidIfNeeded(EntityMaid maid, AutoWorkStateService stateService) {
        if (maid == null || !maid.isAlive() || maid.getScheduleDetail() != Activity.WORK
                || SERVER_REBIND_RESTORED.contains(maid.getUUID())) {
            return 0;
        }
        // Maids that finished loading before the server services were bound
        // still need their legacy "preset id only" state baked.
        if (maid.getData(AutoWorkTaskDataKeys.STATE_KEY) != null) {
            stateService.ensureBoundSnapshot(maid);
        }
        AutoWorkState state = stateService.getState(maid);
        if (!state.enabled()) {
            return 0;
        }
        SERVER_REBIND_RESTORED.add(maid.getUUID());
        requestImmediateEvaluation(maid, "SERVER_REBIND", true);
        return 1;
    }

    /**
     * Internal pairing of a maid with its bound order for this tick.
     * Distinct from {@link TaskDetectionScheduler.MaidPresetJob} so the
     * decision pass can iterate its own list.
     */
    private record AutoWorkMaid(EntityMaid maid, UUID presetId, List<ResourceLocation> order) {
    }

    private record ImmediateEvaluationRequest(String reason, long generation, boolean forceRescan) {
    }
}
