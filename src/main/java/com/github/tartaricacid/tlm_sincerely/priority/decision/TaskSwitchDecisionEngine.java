package com.github.tartaricacid.tlm_sincerely.priority.decision;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
import com.github.tartaricacid.tlm_sincerely.priority.TaskPriorityPreset;
import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionResult;
import com.github.tartaricacid.tlm_sincerely.priority.detection.MaidDetectionCache;
import com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/** The only component allowed to call EntityMaid.setTask for automatic switching. */
public final class TaskSwitchDecisionEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskSwitchDecisionEngine.class);
    private static final int ATTACK_RELEASE_GRACE_TICKS = 20;

    public boolean handleExperimentalAttackPreempt(EntityMaid maid, TaskPriorityPreset preset,
                                                    List<ResourceLocation> sortedTasks,
                                                    MaidDetectionCache cache, MaidSwitchState state,
                                                    long currentTick) {
        if (!PriorityConfig.EXPERIMENTAL_ATTACK_PREEMPT.get()) {
            return false;
        }
        ResourceLocation currentUid = maid.getTask().getUid();
        Optional<SwitchCandidate> attackCandidate = findAttackCandidate(maid, sortedTasks, cache, currentTick, currentUid);
        if (attackCandidate.isPresent()) {
            SwitchCandidate candidate = attackCandidate.get();
            state.beginAttackPreempt(currentUid);
            switchTo(maid, candidate.task(), state, currentTick, "EXPERIMENTAL_ATTACK_PREEMPT");
            LOGGER.debug("[TaskPreempt] maid={} attack={} target={} experimental=true", maid.getUUID(),
                    candidate.uid(), candidate.targetUuid());
            return true;
        }
        if (!state.isAttackPreempted()) {
            return false;
        }

        if (hasConfiguredAttackTarget(maid, sortedTasks, cache, currentTick)) {
            state.markAttackPresent();
            return true;
        }
        state.markAttackMissing(currentTick);
        if (currentTick - state.attackMissingSinceTick() < ATTACK_RELEASE_GRACE_TICKS) {
            return true;
        }

        ResourceLocation suspendedUid = state.suspendedTaskUid();
        if (suspendedUid != null && isAvailable(maid, suspendedUid, cache, currentTick)) {
            resolveTask(suspendedUid).ifPresent(task -> switchTo(maid, task, state, currentTick,
                    "ATTACK_TARGET_LOST_RESTORE"));
        } else {
            findHighestAvailable(maid, sortedTasks, cache, currentTick)
                    .ifPresent(candidate -> switchTo(maid, candidate.task(), state, currentTick,
                            "ATTACK_TARGET_LOST_RESELECT"));
        }
        state.clearAttackPreempt();
        return true;
    }

    public void evaluateNormalSwitch(EntityMaid maid, TaskPriorityPreset preset,
                                     List<ResourceLocation> sortedTasks, MaidDetectionCache cache,
                                     MaidSwitchState state, long currentTick) {
        if (!state.canSwitchNormally(currentTick, PriorityConfig.MINIMUM_TASK_HOLD_TICKS.get())) {
            return;
        }
        Optional<SwitchCandidate> candidate = findHighestAvailable(maid, sortedTasks, cache, currentTick);
        if (candidate.isEmpty()) {
            return;
        }

        ResourceLocation currentUid = maid.getTask().getUid();
        if (candidate.get().uid().equals(currentUid)) {
            return;
        }
        boolean currentConfigured = preset.hasTask(currentUid);
        DetectionResult currentResult = cache.getFresh(currentUid, currentTick);
        if (currentConfigured && currentResult.availability() == Availability.AVAILABLE
                && !isHigherPriority(candidate.get().uid(), currentUid, sortedTasks)) {
            LOGGER.debug("[TaskDecision] maid={} keep={} reason=CURRENT_AVAILABLE", maid.getUUID(), currentUid);
            return;
        }
        if (currentConfigured && currentResult.availability() == Availability.UNKNOWN) {
            return;
        }
        if (currentConfigured && currentResult.availability() == Availability.UNAVAILABLE
                && currentResult.consecutiveConfirmations() < PriorityConfig.UNAVAILABLE_CONFIRMATIONS.get()) {
            return;
        }
        switchTo(maid, candidate.get().task(), state, currentTick,
                currentConfigured && currentResult.availability() == Availability.AVAILABLE
                        ? "HIGHER_PRIORITY_AVAILABLE" : "CURRENT_UNAVAILABLE");
    }

    private Optional<SwitchCandidate> findAttackCandidate(EntityMaid maid, List<ResourceLocation> sortedTasks,
                                                           MaidDetectionCache cache, long currentTick,
                                                           ResourceLocation currentUid) {
        for (ResourceLocation taskUid : sortedTasks) {
            IMaidTask task = resolveTask(taskUid).orElse(null);
            if (!(task instanceof IAttackTask) || !task.isEnable(maid)) {
                continue;
            }
            DetectionResult result = cache.getFresh(taskUid, currentTick);
            if (result.availability() == Availability.AVAILABLE) {
                if (taskUid.equals(currentUid)) {
                    return Optional.empty();
                }
                return Optional.of(new SwitchCandidate(taskUid, task, result.targetEntityUuid()));
            }
        }
        return Optional.empty();
    }

    private boolean hasConfiguredAttackTarget(EntityMaid maid, List<ResourceLocation> sortedTasks,
                                              MaidDetectionCache cache, long currentTick) {
        for (ResourceLocation taskUid : sortedTasks) {
            IMaidTask task = resolveTask(taskUid).orElse(null);
            if (task instanceof IAttackTask && task.isEnable(maid)
                    && cache.getFresh(taskUid, currentTick).availability() == Availability.AVAILABLE) {
                return true;
            }
        }
        return false;
    }

    private Optional<SwitchCandidate> findHighestAvailable(EntityMaid maid, List<ResourceLocation> sortedTasks,
                                                            MaidDetectionCache cache, long currentTick) {
        for (ResourceLocation taskUid : sortedTasks) {
            IMaidTask task = resolveTask(taskUid).orElse(null);
            if (task == null || !task.isEnable(maid)) {
                continue;
            }
            DetectionResult result = cache.getFresh(taskUid, currentTick);
            if (result.availability() == Availability.AVAILABLE
                    && result.consecutiveConfirmations() >= PriorityConfig.AVAILABLE_CONFIRMATIONS.get()) {
                return Optional.of(new SwitchCandidate(taskUid, task, result.targetEntityUuid()));
            }
        }
        return Optional.empty();
    }

    private boolean isAvailable(EntityMaid maid, ResourceLocation taskUid,
                                MaidDetectionCache cache, long currentTick) {
        IMaidTask task = resolveTask(taskUid).orElse(null);
        DetectionResult result = cache.getFresh(taskUid, currentTick);
        return task != null && task.isEnable(maid) && result.availability() == Availability.AVAILABLE
                && result.consecutiveConfirmations() >= PriorityConfig.AVAILABLE_CONFIRMATIONS.get();
    }

    private static Optional<IMaidTask> resolveTask(ResourceLocation taskUid) {
        return TaskManager.findTask(taskUid);
    }

    private static boolean isHigherPriority(ResourceLocation candidate, ResourceLocation current,
                                            List<ResourceLocation> sortedTasks) {
        int candidateIndex = sortedTasks.indexOf(candidate);
        int currentIndex = sortedTasks.indexOf(current);
        return candidateIndex >= 0 && currentIndex >= 0 && candidateIndex < currentIndex;
    }

    private void switchTo(EntityMaid maid, IMaidTask targetTask, MaidSwitchState state,
                          long currentTick, String reason) {
        ResourceLocation currentUid = maid.getTask().getUid();
        if (currentUid.equals(targetTask.getUid())) {
            return;
        }
        maid.setTask(targetTask);
        state.recordSwitch(currentTick);
        LOGGER.debug("[TaskDecision] maid={} current={} selected={} reason={}", maid.getUUID(),
                currentUid, targetTask.getUid(), reason);
    }

    private record SwitchCandidate(ResourceLocation uid, IMaidTask task, java.util.UUID targetUuid) {
    }
}
