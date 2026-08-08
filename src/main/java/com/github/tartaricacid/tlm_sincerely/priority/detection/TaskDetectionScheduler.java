package com.github.tartaricacid.tlm_sincerely.priority.detection;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
import com.github.tartaricacid.tlm_sincerely.priority.TaskDetectionRuntimeState;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPreset;
import com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    /**
     * Per-maid work order. The {@link AutoWorkPreset} may be null when
     * the caller has already verified the maid has no configured tasks
     * (e.g. empty preset) — in that case the scheduler simply skips
     * detection for that maid.
     */
    public record MaidPresetJob(EntityMaid maid, AutoWorkPreset preset) {
    }

    public void tick(TaskDetectionRuntimeState runtime, List<MaidPresetJob> jobs, long currentTick) {
        int remainingBlocks = PriorityConfig.DETECTION_BLOCK_BUDGET_PER_TICK.get();
        int remainingPaths = PriorityConfig.PATH_CHECK_BUDGET_PER_TICK.get();
        List<DetectionJob> detectionJobs = createJobs(runtime, jobs, currentTick);
        if (detectionJobs.isEmpty()) {
            return;
        }

        int start = Math.floorMod(roundRobinStart, detectionJobs.size());
        for (int offset = 0; offset < detectionJobs.size(); offset++) {
            DetectionJob job = detectionJobs.get((start + offset) % detectionJobs.size());
            MaidDetectionCache cache = runtime.getDetectionCache(job.maid());
            if (!cache.isDue(job.taskUid(), currentTick, job.detector().minIntervalTicks())) {
                continue;
            }
            if (job.detector().usesBlockBudget() && remainingBlocks <= 0) {
                continue;
            }

            cache.markAttempt(job.taskUid(), currentTick);
            DetectionContext context = new DetectionContext(job.maid(), (net.minecraft.server.level.ServerLevel) job.maid().level(),
                    currentTick, Math.min(PER_FARM_DETECTOR_BLOCK_BUDGET, remainingBlocks), remainingPaths,
                    cache.getCursor(job.taskUid()));
            DetectionResult result;
            try {
                result = job.detector().detect(context, job.task());
            } catch (RuntimeException exception) {
                if (cache.shouldLogWarning(job.taskUid(), currentTick)) {
                    LOGGER.warn("[TaskDetect] detector failed maid={} task={}",
                            job.maid().getUUID(), job.taskUid(), exception);
                }
                result = DetectionResult.unknown(job.taskUid(), currentTick, "DETECTOR_EXCEPTION");
            }
            remainingBlocks -= context.consumedBlocks();
            remainingPaths -= context.consumedPaths();
            cache.setCursor(job.taskUid(), context.cursor());
            cache.record(result);
            LOGGER.debug("[TaskDetect] maid={} task={} result={} evidence={} ttl={}",
                    job.maid().getUUID(), job.taskUid(), result.availability(), result.evidence(), result.ttlTicks());
        }
        roundRobinStart = (start + 1) % detectionJobs.size();
    }

    private static List<DetectionJob> createJobs(TaskDetectionRuntimeState runtime,
                                                 List<MaidPresetJob> jobs, long currentTick) {
        List<DetectionJob> result = new ArrayList<>();
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
                    if (cache.isDue(taskUid, currentTick, 20)) {
                        cache.markAttempt(taskUid, currentTick);
                        cache.record(DetectionResult.unknown(taskUid, currentTick, "TASK_NOT_FOUND"));
                        if (cache.shouldLogWarning(taskUid, currentTick)) {
                            LOGGER.warn("[TaskDetect] maid={} configured task {} is not registered", maid.getUUID(), taskUid);
                        }
                    }
                    continue;
                }
                TaskWorkDetector detector = TaskWorkDetectorRegistry.resolve(task);
                result.add(new DetectionJob(maid, taskUid, task, detector, taskIndex,
                        task instanceof IAttackTask, taskUid.equals(currentUid)));
            }
        }
        result.sort(Comparator.comparing(DetectionJob::attack, Comparator.reverseOrder())
                .thenComparing(DetectionJob::currentTask, Comparator.reverseOrder())
                .thenComparingInt(DetectionJob::priorityIndex));
        return result;
    }

    private record DetectionJob(EntityMaid maid, ResourceLocation taskUid, IMaidTask task,
                                TaskWorkDetector detector, int priorityIndex, boolean attack,
                                boolean currentTask) {
    }
}
