package com.github.tartaricacid.tlm_sincerely.priority.detection;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
import com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import net.minecraft.world.entity.LivingEntity;

public final class AttackTaskWorkDetector implements TaskWorkDetector {
    @Override
    public boolean supports(IMaidTask task) {
        return task instanceof IAttackTask;
    }

    @Override
    public DetectionResult detect(DetectionContext context, IMaidTask task) {
        if (!(task instanceof IAttackTask)) {
            return DetectionResult.unknown(task.getUid(), context.currentTick(), "NOT_ATTACK_TASK");
        }
        if (!task.isEnable(context.maid())) {
            return new DetectionResult(task.getUid(), Availability.UNAVAILABLE, context.currentTick(),
                    20, 0, "TASK_DISABLED", null, null);
        }
        return IAttackTask.findFirstValidAttackTarget(context.maid())
                .<DetectionResult>map(target -> available(context, task, target))
                .orElseGet(() -> new DetectionResult(task.getUid(), Availability.UNAVAILABLE, context.currentTick(),
                        20, 0, "NO_ATTACK_TARGET", null, null));
    }

    @Override
    public int minIntervalTicks() {
        return PriorityConfig.EXPERIMENTAL_ATTACK_PREEMPT.get() ? 5 : 10;
    }

    private DetectionResult available(DetectionContext context, IMaidTask task, LivingEntity target) {
        return new DetectionResult(task.getUid(), Availability.AVAILABLE, context.currentTick(),
                20, 0, "ATTACK_TARGET", null, target.getUUID());
    }
}
