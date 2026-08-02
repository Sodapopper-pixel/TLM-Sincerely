package com.github.tartaricacid.tlm_sincerely.priority.detection;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;

public interface TaskWorkDetector {
    boolean supports(IMaidTask task);

    DetectionResult detect(DetectionContext context, IMaidTask task);

    default int minIntervalTicks() {
        return 20;
    }

    default boolean usesBlockBudget() {
        return false;
    }
}
