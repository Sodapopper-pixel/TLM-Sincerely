package com.github.tartaricacid.tlm_sincerely.priority.detection;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

public final class MaidDetectionCache {
    private final Map<ResourceLocation, DetectionResult> latestAttempts = new HashMap<>();
    private final Map<ResourceLocation, DetectionResult> definitiveResults = new HashMap<>();
    private final Map<ResourceLocation, TaskScanCursor> cursors = new HashMap<>();
    private final Map<ResourceLocation, Long> lastAttemptTicks = new HashMap<>();
    private final Map<ResourceLocation, Availability> lastDefinitiveAvailability = new HashMap<>();
    private final Map<ResourceLocation, Integer> definitiveConfirmations = new HashMap<>();
    private final Map<ResourceLocation, Long> lastWarningTicks = new HashMap<>();
    private long lastDefinitiveUpdateTick = -1;

    public DetectionResult getFresh(ResourceLocation taskUid, long currentTick) {
        DetectionResult definitive = definitiveResults.get(taskUid);
        if (definitive != null && !definitive.isExpired(currentTick)) {
            return definitive;
        }
        DetectionResult attempt = latestAttempts.get(taskUid);
        if (attempt == null) {
            return DetectionResult.unknown(taskUid, currentTick, "NOT_SAMPLED");
        }
        return attempt.isExpired(currentTick)
                ? DetectionResult.unknown(taskUid, currentTick, "EXPIRED")
                : attempt;
    }

    public void record(DetectionResult result) {
        ResourceLocation taskUid = result.taskUid();
        int confirmations = 0;
        if (result.availability() != Availability.UNKNOWN) {
            Availability previous = lastDefinitiveAvailability.get(taskUid);
            confirmations = result.availability() == previous
                    ? definitiveConfirmations.getOrDefault(taskUid, 0) + 1
                    : 1;
            lastDefinitiveAvailability.put(taskUid, result.availability());
            definitiveConfirmations.put(taskUid, confirmations);
        } else {
            confirmations = definitiveConfirmations.getOrDefault(taskUid, 0);
        }
        DetectionResult recorded = result.withConfirmations(confirmations);
        latestAttempts.put(taskUid, recorded);
        if (result.availability() != Availability.UNKNOWN) {
            definitiveResults.put(taskUid, recorded);
            lastDefinitiveUpdateTick = Math.max(lastDefinitiveUpdateTick, result.sampledTick());
        }
    }

    public boolean hasDefinitiveUpdateAt(long currentTick) {
        return lastDefinitiveUpdateTick == currentTick;
    }

    public boolean isDue(ResourceLocation taskUid, long currentTick, int intervalTicks) {
        if (cursors.containsKey(taskUid)) {
            return true;
        }
        Long lastAttempt = lastAttemptTicks.get(taskUid);
        return lastAttempt == null || currentTick < lastAttempt || currentTick - lastAttempt >= intervalTicks;
    }

    public void markAttempt(ResourceLocation taskUid, long currentTick) {
        lastAttemptTicks.put(taskUid, currentTick);
    }

    public TaskScanCursor getCursor(ResourceLocation taskUid) {
        return cursors.get(taskUid);
    }

    public void setCursor(ResourceLocation taskUid, TaskScanCursor cursor) {
        if (cursor == null) {
            cursors.remove(taskUid);
        } else {
            cursors.put(taskUid, cursor);
        }
    }

    public boolean shouldLogWarning(ResourceLocation taskUid, long currentTick) {
        Long lastWarning = lastWarningTicks.get(taskUid);
        if (lastWarning != null && currentTick >= lastWarning && currentTick - lastWarning < 200) {
            return false;
        }
        lastWarningTicks.put(taskUid, currentTick);
        return true;
    }

    public void clear() {
        latestAttempts.clear();
        definitiveResults.clear();
        cursors.clear();
        lastAttemptTicks.clear();
        lastDefinitiveAvailability.clear();
        definitiveConfirmations.clear();
        lastWarningTicks.clear();
        lastDefinitiveUpdateTick = -1;
    }
}
