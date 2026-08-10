package com.github.tartaricacid.tlm_sincerely.priority.detection;

import com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IFarmTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public registration point for addon detectors. Exact task UID registrations
 * take precedence over interface and class fallbacks.
 */
public final class TaskWorkDetectorRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskWorkDetectorRegistry.class);
    private static final Map<ResourceLocation, TaskWorkDetector> UID_DETECTORS = new LinkedHashMap<>();
    private static final Map<Class<?>, TaskWorkDetector> FALLBACK_DETECTORS = new LinkedHashMap<>();
    private static final TaskWorkDetector UNKNOWN_DETECTOR = new TaskWorkDetector() {
        @Override
        public boolean supports(IMaidTask task) {
            return true;
        }

        @Override
        public DetectionResult detect(DetectionContext context, IMaidTask task) {
            return DetectionResult.unknown(task.getUid(), context.currentTick(), "NO_DETECTOR");
        }
    };

    static {
        registerFallback(IAttackTask.class, new AttackTaskWorkDetector());
        registerFallback(IFarmTask.class, new FarmTaskWorkDetector());
    }

    private TaskWorkDetectorRegistry() {
    }

    public static synchronized boolean register(ResourceLocation taskUid, TaskWorkDetector detector) {
        if (UID_DETECTORS.containsKey(taskUid)) {
            LOGGER.warn("[TaskDetect] duplicate detector registration for {}, use replace instead", taskUid);
            return false;
        }
        UID_DETECTORS.put(taskUid, detector);
        return true;
    }

    public static synchronized boolean registerFallback(Class<?> taskType, TaskWorkDetector detector) {
        if (FALLBACK_DETECTORS.containsKey(taskType)) {
            LOGGER.warn("[TaskDetect] duplicate fallback detector registration for {}, use replaceFallback instead",
                    taskType.getName());
            return false;
        }
        FALLBACK_DETECTORS.put(taskType, detector);
        return true;
    }

    public static synchronized void replace(ResourceLocation taskUid, TaskWorkDetector detector) {
        UID_DETECTORS.put(taskUid, detector);
    }

    public static synchronized void replaceFallback(Class<?> taskType, TaskWorkDetector detector) {
        FALLBACK_DETECTORS.put(taskType, detector);
    }

    public static synchronized TaskWorkDetector resolve(IMaidTask task) {
        TaskWorkDetector exact = UID_DETECTORS.get(task.getUid());
        if (exact != null && exact.supports(task)) {
            return exact;
        }
        for (Map.Entry<Class<?>, TaskWorkDetector> entry : FALLBACK_DETECTORS.entrySet()) {
            if (entry.getKey().isInstance(task) && entry.getValue().supports(task)) {
                return entry.getValue();
            }
        }
        return UNKNOWN_DETECTOR;
    }

    /** Returns whether {@code detector} is the safe no-detector fallback. */
    public static boolean isUnknown(TaskWorkDetector detector) {
        return detector == UNKNOWN_DETECTOR;
    }
}
