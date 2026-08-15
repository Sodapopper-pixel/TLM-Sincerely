package com.github.tartaricacid.tlm_sincerely.priority.detection;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
import com.github.tartaricacid.tlm_sincerely.priority.TaskDetectionRuntimeState;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPreset;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.compat.AutoWorkCompatService;
import com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes all detector work on the server thread within fixed global
 * budgets.
 *
 * <p>T-2 B1: each maid now brings its own {@link AutoWorkPreset}; the
 * scheduler no longer reads a single shared task id list. Per-maid jobs
 * are flattened into the existing priority / attack / current-task
 * ordering so the budget dispatch remains fair across maids.
 */
public final class TaskDetectionScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskDetectionScheduler.class);
    private static final int PER_FARM_DETECTOR_BLOCK_BUDGET = 64;
    private int roundRobinStart;
    private final Map<java.util.UUID, Integer> maidJobRoundRobinStarts = new HashMap<>();

    /**
     * Per-maid work order. The {@link AutoWorkPreset} may be null when
     * the caller has already verified the maid has no configured tasks
     * (e.g. empty preset) — in that case the scheduler simply skips
     * detection for that maid.
     */
    public record MaidPresetJob(EntityMaid maid, AutoWorkPreset preset) {
    }

    public void clearMaid(java.util.UUID maidId) {
        maidJobRoundRobinStarts.remove(maidId);
    }

    public void clear() {
        roundRobinStart = 0;
        maidJobRoundRobinStarts.clear();
    }

    public SchedulerStats tick(TaskDetectionRuntimeState runtime, List<MaidPresetJob> jobs, long currentTick) {
        int blockCap = PriorityConfig.DETECTION_BLOCK_BUDGET_PER_TICK.get();
        int pathCap = PriorityConfig.PATH_CHECK_BUDGET_PER_TICK.get();
        int remainingBlocks = blockCap;
        int remainingPaths = pathCap;
        for (MaidPresetJob job : jobs) {
            if (job.preset() != null) {
                runtime.prepareForceRescan(job.maid(), job.preset().getOrder());
            }
        }
        int forceRescanMaidCount = runtime.forceRescanMaidCount();
        JobBuildResult built = createJobs(runtime, jobs, currentTick);
        List<DetectionJob> detectionJobs = built.jobs();
        if (detectionJobs.isEmpty()) {
            return new SchedulerStats(0, 0, 0, 0, blockCap, 0, pathCap,
                    0, 0, forceRescanMaidCount, built.skippedNoDetector(), built.skippedPolicy());
        }

        List<MaidQueue> queues = createMaidQueues(detectionJobs);
        queues.sort(Comparator.comparing(MaidQueue::hasForceRescan, Comparator.reverseOrder()));
        int start = Math.floorMod(roundRobinStart, queues.size());
        int blockShare = share(blockCap, queues.size());
        int pathShare = share(pathCap, queues.size());
        Map<java.util.UUID, MaidBudget> maidBudgets = new LinkedHashMap<>();
        for (MaidQueue queue : queues) {
            maidBudgets.put(queue.maid().getUUID(), new MaidBudget(blockShare, pathShare));
        }

        int dueJobCount = 0;
        for (DetectionJob job : detectionJobs) {
            MaidDetectionCache cache = runtime.getDetectionCache(job.maid());
            if (runtime.isForceRescanPending(job.maid(), job.taskUid())
                    || cache.isDue(job.taskUid(), currentTick, job.detector().minIntervalTicks())) {
                dueJobCount++;
            }
        }

        int blockBudgetExhausted = 0;
        int pathBudgetExhausted = 0;
        Set<DetectionJob> attempted = new HashSet<>();
        boolean overflow = false;
        while (true) {
            boolean progressed = false;
            for (int offset = 0; offset < queues.size(); offset++) {
                MaidQueue queue = queues.get((start + offset) % queues.size());
                MaidDetectionCache cache = runtime.getDetectionCache(queue.maid());
                DetectionJob job = queue.nextDueJob(runtime, cache, currentTick, attempted);
                if (job == null) {
                    continue;
                }
                MaidBudget maidBudget = maidBudgets.get(queue.maid().getUUID());
                if (job.detector().usesBlockBudget()
                        && (remainingBlocks <= 0 || maidBudget.remainingBlocks <= 0)) {
                    continue;
                }

                attempted.add(job);
                cache.markAttempt(job.taskUid(), currentTick);
                DetectionContext context = new DetectionContext(job.maid(),
                        (net.minecraft.server.level.ServerLevel) job.maid().level(), currentTick,
                        Math.min(PER_FARM_DETECTOR_BLOCK_BUDGET, Math.min(remainingBlocks, maidBudget.remainingBlocks)),
                        Math.min(remainingPaths, maidBudget.remainingPaths), cache.getCursor(job.taskUid()));
                DetectionResult result;
                try {
                    result = job.detector().detect(context, job.task());
                } catch (RuntimeException | LinkageError exception) {
                    if (cache.shouldLogWarning(job.taskUid(), currentTick)) {
                        LOGGER.warn("[TaskDetect] detector failed maid={} task={}",
                                job.maid().getUUID(), job.taskUid(), exception);
                    }
                    result = DetectionResult.unknown(job.taskUid(), currentTick, "DETECTOR_EXCEPTION");
                }
                remainingBlocks -= context.consumedBlocks();
                remainingPaths -= context.consumedPaths();
                maidBudget.consume(context.consumedBlocks(), context.consumedPaths());
                cache.setCursor(job.taskUid(), context.cursor());
                long generation = runtime.getDetectionGeneration(job.maid());
                cache.record(result, generation);
                if (job.detector().usesBlockBudget()) {
                    cache.recordScanOutcome(job.taskUid(), result, context.cursor());
                }
                runtime.completeForceRescanTask(job.maid(), job.taskUid());
                if (result.evidence().contains("BLOCK_BUDGET_EXHAUSTED")) {
                    blockBudgetExhausted++;
                }
                if (result.evidence().contains("PATH_BUDGET_EXHAUSTED")) {
                    pathBudgetExhausted++;
                }
                if (cache.hasRepeatedBudgetExhaustion(job.taskUid()) && cache.shouldLogWarning(job.taskUid(), currentTick)) {
                    LOGGER.warn("[TaskBudget] maid={} task={} repeated_budget_exhaustion scanCycles={}",
                            job.maid().getUUID(), job.taskUid(), cache.completedScanCycles(job.taskUid()));
                }
                LOGGER.debug("[TaskDetect] maid={} generation={} task={} result={} evidence={} ttl={}",
                        job.maid().getUUID(), generation, job.taskUid(), result.availability(), result.evidence(),
                        result.ttlTicks());
                progressed = true;
            }
            if (progressed) {
                continue;
            }
            if (!overflow && (remainingBlocks > 0 || remainingPaths > 0)) {
                overflow = true;
                for (MaidBudget budget : maidBudgets.values()) {
                    budget.release(remainingBlocks, remainingPaths);
                }
                continue;
            }
            break;
        }
        roundRobinStart = (start + 1) % queues.size();
        for (MaidQueue queue : queues) {
            maidJobRoundRobinStarts.put(queue.maid().getUUID(), queue.nextJobIndex());
        }
        return new SchedulerStats(queues.size(), detectionJobs.size(), dueJobCount,
                blockCap - remainingBlocks, blockCap, pathCap - remainingPaths, pathCap,
                blockBudgetExhausted, pathBudgetExhausted, forceRescanMaidCount,
                built.skippedNoDetector(), built.skippedPolicy());
    }

    private static JobBuildResult createJobs(TaskDetectionRuntimeState runtime,
                                             List<MaidPresetJob> jobs, long currentTick) {
        List<DetectionJob> result = new ArrayList<>();
        int skippedNoDetector = 0;
        int skippedPolicy = 0;
        for (MaidPresetJob mpj : jobs) {
            EntityMaid maid = mpj.maid();
            AutoWorkPreset preset = mpj.preset();
            if (preset == null) {
                continue;
            }
            List<ResourceLocation> order = preset.getOrder();
            if (order.isEmpty()) {
                continue;
            }
            ResourceLocation currentUid = maid.getTask().getUid();
            for (int taskIndex = 0; taskIndex < order.size(); taskIndex++) {
                ResourceLocation taskUid = order.get(taskIndex);
                IMaidTask task = TaskManager.findTask(taskUid).orElse(null);
                if (task == null) {
                    MaidDetectionCache cache = runtime.getDetectionCache(maid);
                    boolean forceRescan = runtime.isForceRescanPending(maid, taskUid);
                    if (forceRescan || cache.isDue(taskUid, currentTick, 20)) {
                        cache.markAttempt(taskUid, currentTick);
                        cache.record(DetectionResult.unknown(taskUid, currentTick, "TASK_NOT_FOUND"),
                                runtime.getDetectionGeneration(maid));
                        runtime.completeForceRescanTask(maid, taskUid);
                        if (cache.shouldLogWarning(taskUid, currentTick)) {
                            LOGGER.warn("[TaskDetect] maid={} configured task {} is not registered", maid.getUUID(), taskUid);
                        }
                    }
                    continue;
                }
                TaskWorkDetector detector = TaskWorkDetectorRegistry.resolve(task);
                AutoWorkCompatService compatService = AutoWorkCompatService.getOrNull(maid.level().getServer());
                if (compatService != null && !compatService.isAutoScheduleAllowed(taskUid)) {
                    MaidDetectionCache cache = runtime.getDetectionCache(maid);
                    boolean forceRescan = runtime.isForceRescanPending(maid, taskUid);
                    if (forceRescan || cache.isDue(taskUid, currentTick, 20)) {
                        cache.markAttempt(taskUid, currentTick);
                        String reason = compatService.getEntry(taskUid) == null
                                ? "COMPAT_UNSUPPORTED" : compatService.getEntry(taskUid).reason();
                        cache.record(DetectionResult.unknown(taskUid, currentTick, reason),
                                runtime.getDetectionGeneration(maid));
                        runtime.completeForceRescanTask(maid, taskUid);
                        if (cache.shouldLogWarning(taskUid, currentTick)) {
                            LOGGER.warn("[TaskDetect] maid={} task={} excluded by compatibility policy reason={}",
                                    maid.getUUID(), taskUid, reason);
                        }
                    }
                    skippedPolicy++;
                    continue;
                }
                if (TaskWorkDetectorRegistry.isUnknown(detector)) {
                    MaidDetectionCache cache = runtime.getDetectionCache(maid);
                    boolean forceRescan = runtime.isForceRescanPending(maid, taskUid);
                    if (forceRescan || cache.isDue(taskUid, currentTick, 20)) {
                        cache.markAttempt(taskUid, currentTick);
                        cache.record(DetectionResult.unknown(taskUid, currentTick, "NO_DETECTOR"),
                                runtime.getDetectionGeneration(maid));
                        runtime.completeForceRescanTask(maid, taskUid);
                    }
                    if (cache.shouldLogWarning(taskUid, currentTick)) {
                        LOGGER.warn("[TaskDetect] maid={} configured task {} has no detector; it remains safe to keep as "
                                        + "the current task but will never be auto-selected. "
                                        + "Its addon must register TaskWorkDetectorRegistry.register(...)",
                                maid.getUUID(), taskUid);
                    }
                    skippedNoDetector++;
                    continue;
                }
                result.add(new DetectionJob(maid, taskUid, task, detector, taskIndex,
                        task instanceof IAttackTask, taskUid.equals(currentUid),
                        runtime.isForceRescanPending(maid, taskUid)));
            }
        }
        result.sort(Comparator.comparing(DetectionJob::forceRescan, Comparator.reverseOrder())
                .thenComparing(DetectionJob::attack, Comparator.reverseOrder())
                .thenComparing(DetectionJob::currentTask, Comparator.reverseOrder())
                .thenComparingInt(DetectionJob::priorityIndex));
        return new JobBuildResult(result, skippedNoDetector, skippedPolicy);
    }

    private List<MaidQueue> createMaidQueues(List<DetectionJob> jobs) {
        Map<java.util.UUID, MaidQueue> queues = new LinkedHashMap<>();
        for (DetectionJob job : jobs) {
            queues.computeIfAbsent(job.maid().getUUID(), unused -> new MaidQueue(job.maid(),
                    maidJobRoundRobinStarts.getOrDefault(job.maid().getUUID(), 0))).jobs.add(job);
        }
        for (MaidQueue queue : queues.values()) {
            queue.normalizeIndex();
        }
        return new ArrayList<>(queues.values());
    }

    private static int share(int budget, int maidCount) {
        return budget <= 0 ? 0 : (budget + maidCount - 1) / maidCount;
    }

    public record SchedulerStats(int maidCount, int jobCount, int dueJobCount,
                                 int blockUsed, int blockCap, int pathUsed, int pathCap,
                                 int blockBudgetExhausted, int pathBudgetExhausted,
                                 int forceRescanMaidCount, int skippedNoDetector, int skippedPolicy) {
    }

    private record JobBuildResult(List<DetectionJob> jobs, int skippedNoDetector, int skippedPolicy) {
    }

    private record DetectionJob(EntityMaid maid, ResourceLocation taskUid, IMaidTask task,
                                TaskWorkDetector detector, int priorityIndex, boolean attack,
                                boolean currentTask, boolean forceRescan) {
    }

    private static final class MaidQueue {
        private final EntityMaid maid;
        private final List<DetectionJob> jobs = new ArrayList<>();
        private int nextJobIndex;

        private MaidQueue(EntityMaid maid, int nextJobIndex) {
            this.maid = maid;
            this.nextJobIndex = nextJobIndex;
        }

        private EntityMaid maid() {
            return maid;
        }

        private int nextJobIndex() {
            return nextJobIndex;
        }

        private void normalizeIndex() {
            nextJobIndex = jobs.isEmpty() ? 0 : Math.floorMod(nextJobIndex, jobs.size());
        }

        private boolean hasForceRescan() {
            return jobs.stream().anyMatch(DetectionJob::forceRescan);
        }

        private DetectionJob nextDueJob(TaskDetectionRuntimeState runtime, MaidDetectionCache cache,
                                        long currentTick, Set<DetectionJob> attempted) {
            DetectionJob exhaustedFallback = null;
            int fallbackIndex = -1;
            for (int offset = 0; offset < jobs.size(); offset++) {
                int index = Math.floorMod(nextJobIndex + offset, jobs.size());
                DetectionJob candidate = jobs.get(index);
                if (attempted.contains(candidate)) {
                    continue;
                }
                boolean forceRescan = runtime.isForceRescanPending(maid, candidate.taskUid());
                if (!forceRescan && !cache.isDue(candidate.taskUid(), currentTick,
                        candidate.detector().minIntervalTicks())) {
                    continue;
                }
                if (!forceRescan && cache.hasRepeatedBudgetExhaustion(candidate.taskUid())) {
                    exhaustedFallback = candidate;
                    fallbackIndex = index;
                    continue;
                }
                nextJobIndex = (index + 1) % jobs.size();
                return candidate;
            }
            if (exhaustedFallback != null) {
                nextJobIndex = (fallbackIndex + 1) % jobs.size();
            }
            return exhaustedFallback;
        }
    }

    private static final class MaidBudget {
        private int remainingBlocks;
        private int remainingPaths;

        private MaidBudget(int remainingBlocks, int remainingPaths) {
            this.remainingBlocks = remainingBlocks;
            this.remainingPaths = remainingPaths;
        }

        private void consume(int blocks, int paths) {
            remainingBlocks -= blocks;
            remainingPaths -= paths;
        }

        private void release(int blocks, int paths) {
            remainingBlocks = blocks;
            remainingPaths = paths;
        }
    }
}
