package com.github.tartaricacid.tlm_sincerely.priority.decision;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkInternalSetTaskGuard;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPreset;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.compat.AutoWorkCompatService;
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

/**
 * The only component allowed to call {@code EntityMaid.setTask} for
 * automatic switching (T-2 B1).
 *
 * <p>Reads the per-maid {@link AutoWorkPreset} provided by the handler
 * and uses its list order as the authoritative priority. Numeric
 * priority fields are gone; index 0 in {@link AutoWorkPreset#getOrder()}
 * is the highest priority.
 *
 * <p>All {@code setTask} writes are wrapped in
 * {@link AutoWorkInternalSetTaskGuard} so future external-task
 * compatibility layers can distinguish internal writes from external
 * ones without relying on brittle heuristics like "current task is the
 * virtual auto-switch task" (which we explicitly do not register).
 */
public final class TaskSwitchDecisionEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskSwitchDecisionEngine.class);
    private static final int ATTACK_RELEASE_GRACE_TICKS = 20;

    public boolean handleExperimentalAttackPreempt(EntityMaid maid, AutoWorkPreset preset,
                                                     List<ResourceLocation> sortedTasks,
                                                     MaidDetectionCache cache, MaidSwitchState state,
                                                     long currentTick, long generation) {
        if (!PriorityConfig.EXPERIMENTAL_ATTACK_PREEMPT.get()) {
            return false;
        }
        ResourceLocation currentUid = maid.getTask().getUid();
        Optional<SwitchCandidate> attackCandidate = findAttackCandidate(maid, sortedTasks, cache, currentTick,
                generation, currentUid);
        if (attackCandidate.isPresent()) {
            SwitchCandidate candidate = attackCandidate.get();
            state.beginAttackPreempt(currentUid);
            switchTo(maid, candidate.task(), state, currentTick, generation, "EXPERIMENTAL_ATTACK_PREEMPT", false);
            LOGGER.debug("[TaskPreempt] maid={} attack={} target={} experimental=true", maid.getUUID(),
                    candidate.uid(), candidate.targetUuid());
            return true;
        }
        if (!state.isAttackPreempted()) {
            return false;
        }

        if (hasConfiguredAttackTarget(maid, sortedTasks, cache, currentTick, generation)) {
            state.markAttackPresent();
            return true;
        }
        state.markAttackMissing(currentTick);
        if (currentTick - state.attackMissingSinceTick() < ATTACK_RELEASE_GRACE_TICKS) {
            return true;
        }

        ResourceLocation suspendedUid = state.suspendedTaskUid();
        if (suspendedUid != null && isAvailable(maid, suspendedUid, cache, currentTick, generation)) {
            resolveTask(suspendedUid).ifPresent(task -> switchTo(maid, task, state, currentTick,
                    generation, "ATTACK_TARGET_LOST_RESTORE", false));
        } else {
            findHighestAvailable(maid, sortedTasks, cache, currentTick, generation)
                    .ifPresent(candidate -> switchTo(maid, candidate.task(), state, currentTick,
                            generation, "ATTACK_TARGET_LOST_RESELECT", false));
        }
        state.clearAttackPreempt();
        return true;
    }

    public void evaluateNormalSwitch(EntityMaid maid, AutoWorkPreset preset,
                                      List<ResourceLocation> sortedTasks, MaidDetectionCache cache,
                                      MaidSwitchState state, long currentTick, long generation) {
        if (!state.canSwitchNormally(currentTick, PriorityConfig.MINIMUM_TASK_HOLD_TICKS.get())) {
            return;
        }
        if (state.isReverseCooldownActive(currentTick)) {
            LOGGER.debug("[TaskStability] maid={} cooldownUntil={} reason={} action=KEEP_CURRENT",
                    maid.getUUID(), state.reverseCooldownEndTick(), state.lastSwitchReason());
            return;
        }
        Optional<SwitchCandidate> candidate = findHighestAvailable(maid, sortedTasks, cache, currentTick, generation);
        if (candidate.isEmpty()) {
            return;
        }
        SwitchCandidate chosen = candidate.get();
        ResourceLocation currentUid = maid.getTask().getUid();
        if (chosen.uid().equals(currentUid)) {
            return;
        }
        // "Current real task in this maid's preset" is the key safety
        // boundary. Preset membership decides whether the current task is an
        // intentionally configured selection (which keeps its priority
        // protection) or an external/manual selection (which may always be
        // replaced once a confirmed candidate exists). The candidate itself
        // has already been filtered through the preset and reached its
        // AVAILABLE confirmation threshold before this method is reached.
        boolean currentConfigured = preset != null && preset.hasTask(currentUid);
        DetectionResult currentResult = cache.getFresh(currentUid, generation, currentTick);
        boolean currentIsIdle = currentUid.equals(TaskManager.getIdleTask().getUid());
        // Rule 1: current task is not in the preset and not idle. An external
        // addon or direct code path may have selected a real task while auto
        // work is enabled; such tasks are never sampled, so UNKNOWN used to
        // block switching forever (EXTERNAL_UNKNOWN_CURRENT). Auto work being
        // enabled plus a confirmed candidate is now enough to take over the
        // configured slot; the reason distinguishes this from preset-driven
        // replacements. AVAILABLE/UNAVAILABLE external tasks keep the
        // original fall-through behaviour below (no priority protection).
        if (!currentConfigured && !currentIsIdle) {
            if (currentResult.availability() == Availability.UNKNOWN) {
                if (suppressedByReverseStability(maid, state, currentUid, chosen.uid(), currentTick)) {
                    return;
                }
                LOGGER.debug("[TaskDecision] maid={} generation={} keep=false from={} to={} reason=EXTERNAL_UNKNOWN_REPLACED",
                        maid.getUUID(), generation, currentUid, chosen.uid());
                switchTo(maid, chosen.task(), state, currentTick, generation, "EXTERNAL_UNKNOWN_REPLACED", true);
                return;
            }
        } else if (currentConfigured) {
            // Rule 3: the current task is in the preset but is no longer a
            // usable selection (unregistered, disabled, or no longer allowed
            // by the compat layer). Cached availability is untrustworthy for
            // such tasks, so any confirmed candidate may replace it.
            boolean currentUsable = resolveTask(currentUid)
                    .map(task -> task.isEnable(maid) && isAutoScheduleAllowed(maid, currentUid))
                    .orElse(false);
            if (!currentUsable) {
                if (suppressedByReverseStability(maid, state, currentUid, chosen.uid(), currentTick)) {
                    return;
                }
                LOGGER.debug("[TaskDecision] maid={} generation={} keep=false from={} to={} reason=CURRENT_UNSUPPORTED_OR_DISABLED",
                        maid.getUUID(), generation, currentUid, chosen.uid());
                switchTo(maid, chosen.task(), state, currentTick, generation,
                        "CURRENT_UNSUPPORTED_OR_DISABLED", true);
                return;
            }
            // Rule 4: keep the original priority protection for an AVAILABLE
            // configured task: only a strictly higher-priority candidate may
            // take over.
            if (currentResult.availability() == Availability.AVAILABLE) {
                if (!isHigherPriority(chosen.uid(), currentUid, sortedTasks)) {
                    LOGGER.debug("[TaskDecision] maid={} generation={} keep=true from={} to={} reason=CURRENT_AVAILABLE",
                            maid.getUUID(), generation, currentUid, chosen.uid());
                    return;
                }
                if (suppressedByReverseStability(maid, state, currentUid, chosen.uid(), currentTick)) {
                    return;
                }
                LOGGER.debug("[TaskDecision] maid={} generation={} keep=false from={} to={} reason=HIGHER_PRIORITY_AVAILABLE",
                        maid.getUUID(), generation, currentUid, chosen.uid());
                switchTo(maid, chosen.task(), state, currentTick, generation, "HIGHER_PRIORITY_AVAILABLE", true);
                return;
            }
            // Rule 4: keep the UNAVAILABLE confirmation gate for a configured
            // task before leaving it.
            if (currentResult.availability() == Availability.UNAVAILABLE) {
                if (currentResult.consecutiveConfirmations() < PriorityConfig.UNAVAILABLE_CONFIRMATIONS.get()) {
                    LOGGER.debug("[TaskDecision] maid={} generation={} keep=true from={} confirmations={} reason=CURRENT_UNAVAILABLE_CONFIRMING",
                            maid.getUUID(), generation, currentUid, currentResult.consecutiveConfirmations());
                    return;
                }
                if (suppressedByReverseStability(maid, state, currentUid, chosen.uid(), currentTick)) {
                    return;
                }
                LOGGER.debug("[TaskDecision] maid={} generation={} keep=false from={} to={} reason=CURRENT_UNAVAILABLE",
                        maid.getUUID(), generation, currentUid, chosen.uid());
                switchTo(maid, chosen.task(), state, currentTick, generation, "CURRENT_UNAVAILABLE", true);
                return;
            }
            // Rule 2: the current configured task is UNKNOWN but still exists,
            // is enabled and is allowed by the compat layer (rule 3 already
            // filtered the broken cases). Conservative balance: only a
            // strictly higher-priority candidate may replace it; a
            // lower-priority candidate keeps the current task.
            if (!isHigherPriority(chosen.uid(), currentUid, sortedTasks)) {
                LOGGER.debug("[TaskDecision] maid={} generation={} keep=true from={} to={} reason=CURRENT_UNKNOWN_KEEP",
                        maid.getUUID(), generation, currentUid, chosen.uid());
                return;
            }
            if (suppressedByReverseStability(maid, state, currentUid, chosen.uid(), currentTick)) {
                return;
            }
            LOGGER.debug("[TaskDecision] maid={} generation={} keep=false from={} to={} reason=HIGHER_PRIORITY_OVER_UNKNOWN",
                    maid.getUUID(), generation, currentUid, chosen.uid());
            switchTo(maid, chosen.task(), state, currentTick, generation, "HIGHER_PRIORITY_OVER_UNKNOWN", true);
            return;
        }
        // Fall-through: current task is not in the preset, not idle and not
        // UNKNOWN (AVAILABLE or UNAVAILABLE). Keep the original behaviour of
        // replacing it with the confirmed candidate; the reason is labelled
        // as an external-current replacement instead of the misleading
        // CURRENT_UNAVAILABLE.
        if (suppressedByReverseStability(maid, state, currentUid, chosen.uid(), currentTick)) {
            return;
        }
        LOGGER.debug("[TaskDecision] maid={} generation={} keep=false from={} to={} reason=EXTERNAL_CURRENT_REPLACED",
                maid.getUUID(), generation, currentUid, chosen.uid());
        switchTo(maid, chosen.task(), state, currentTick, generation, "EXTERNAL_CURRENT_REPLACED", true);
    }

    /**
     * Reverse-switch suppression guard shared by every normal switch branch:
     * an A-to-B-to-A reversal inside the window that crosses the threshold
     * starts a finite cooldown and suppresses this switch.
     */
    private boolean suppressedByReverseStability(EntityMaid maid, MaidSwitchState state,
                                                 ResourceLocation currentUid, ResourceLocation candidateUid,
                                                 long currentTick) {
        if (state.shouldSuppressReverseSwitch(currentUid, candidateUid, currentTick,
                PriorityConfig.REVERSE_SWITCH_WINDOW_TICKS.get(), PriorityConfig.REVERSE_SWITCH_THRESHOLD.get(),
                PriorityConfig.REVERSE_SWITCH_COOLDOWN_TICKS.get())) {
            LOGGER.warn("[TaskStability] maid={} from={} to={} reverseCount={} cooldownUntil={} action=SUPPRESS",
                    maid.getUUID(), currentUid, candidateUid, state.reverseSwitchCount(),
                    state.reverseCooldownEndTick());
            return true;
        }
        return false;
    }

    private Optional<SwitchCandidate> findAttackCandidate(EntityMaid maid, List<ResourceLocation> sortedTasks,
                                                            MaidDetectionCache cache, long currentTick,
                                                            long generation, ResourceLocation currentUid) {
        for (ResourceLocation taskUid : sortedTasks) {
            IMaidTask task = resolveTask(taskUid).orElse(null);
            if (!(task instanceof IAttackTask) || !isAutoScheduleAllowed(maid, taskUid) || !task.isEnable(maid)) {
                continue;
            }
            DetectionResult result = cache.getFresh(taskUid, generation, currentTick);
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
                                               MaidDetectionCache cache, long currentTick, long generation) {
        for (ResourceLocation taskUid : sortedTasks) {
            IMaidTask task = resolveTask(taskUid).orElse(null);
            if (task instanceof IAttackTask && isAutoScheduleAllowed(maid, taskUid) && task.isEnable(maid)
                    && cache.getFresh(taskUid, generation, currentTick).availability() == Availability.AVAILABLE) {
                return true;
            }
        }
        return false;
    }

    private Optional<SwitchCandidate> findHighestAvailable(EntityMaid maid, List<ResourceLocation> sortedTasks,
                                                             MaidDetectionCache cache, long currentTick, long generation) {
        for (ResourceLocation taskUid : sortedTasks) {
            IMaidTask task = resolveTask(taskUid).orElse(null);
            if (task == null || !isAutoScheduleAllowed(maid, taskUid) || !task.isEnable(maid)) {
                continue;
            }
            DetectionResult result = cache.getFresh(taskUid, generation, currentTick);
            if (result.availability() == Availability.AVAILABLE
                    && result.consecutiveConfirmations() >= PriorityConfig.AVAILABLE_CONFIRMATIONS.get()) {
                return Optional.of(new SwitchCandidate(taskUid, task, result.targetEntityUuid()));
            }
        }
        return Optional.empty();
    }

    private boolean isAvailable(EntityMaid maid, ResourceLocation taskUid,
                                 MaidDetectionCache cache, long currentTick, long generation) {
        IMaidTask task = resolveTask(taskUid).orElse(null);
        DetectionResult result = cache.getFresh(taskUid, generation, currentTick);
        return task != null && isAutoScheduleAllowed(maid, taskUid) && task.isEnable(maid)
                && result.availability() == Availability.AVAILABLE
                && result.consecutiveConfirmations() >= PriorityConfig.AVAILABLE_CONFIRMATIONS.get();
    }

    private static boolean isAutoScheduleAllowed(EntityMaid maid, ResourceLocation taskUid) {
        AutoWorkCompatService service = AutoWorkCompatService.getOrNull(maid.level().getServer());
        return service == null || service.isAutoScheduleAllowed(taskUid);
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
                          long currentTick, long generation, String reason, boolean normalSwitch) {
        ResourceLocation currentUid = maid.getTask().getUid();
        if (currentUid.equals(targetTask.getUid())) {
            return;
        }
        // Wrap the setTask call in the internal marker so future
        // external-task compatibility layers can recognise our write.
        // The guard is cleared in a finally-equivalent by the helper.
        ResourceLocation targetUid = targetTask.getUid();
        AutoWorkInternalSetTaskGuard.runInternal(maid.getUUID(), targetUid, () -> maid.setTask(targetTask));
        state.recordSwitch(currentTick, currentUid, targetUid, reason, normalSwitch,
                PriorityConfig.REVERSE_SWITCH_WINDOW_TICKS.get());
        LOGGER.info("[TaskDecision] maid={} generation={} current={} selected={} reason={}", maid.getUUID(),
                generation, currentUid, targetUid, reason);
    }

    private record SwitchCandidate(ResourceLocation uid, IMaidTask task, java.util.UUID targetUuid) {
    }
}
