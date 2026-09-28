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
    private final Map<ResourceLocation, Integer> completedScanCycles = new HashMap<>();
    private final Map<ResourceLocation, Integer> consecutiveBudgetExhaustions = new HashMap<>();
    private final Map<ResourceLocation, Long> lastWarningTicks = new HashMap<>();
    private long lastDefinitiveUpdateTick = -1;
    private long activeGeneration;
    private long staleIgnoredCount;

    public DetectionResult getFresh(ResourceLocation taskUid, long generation, long currentTick) {
        if (activeGeneration != generation) {
            staleIgnoredCount++;
            return DetectionResult.unknown(taskUid, currentTick, "STALE_GENERATION");
        }
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

    public void record(DetectionResult result, long generation) {
        if (activeGeneration != generation) {
            clearDetectionData();
            activeGeneration = generation;
        }
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

    public boolean hasDefinitiveUpdateAt(long generation, long currentTick) {
        return activeGeneration == generation && lastDefinitiveUpdateTick == currentTick;
    }

    public int freshResultCount(long generation, long currentTick) {
        if (activeGeneration != generation) {
            return 0;
        }
        int count = 0;
        for (DetectionResult result : latestAttempts.values()) {
            if (!result.isExpired(currentTick)) {
                count++;
            }
        }
        return count;
    }

    public long staleIgnoredCount() {
        return staleIgnoredCount;
    }

    public void invalidateForGeneration(long generation) {
        clearDetectionData();
        activeGeneration = generation;
        staleIgnoredCount = 0;
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

    /**
     * Records farm scan completion and repeated budget pressure for scheduler
     * diagnostics. 只有 block 预算耗尽计入连续耗尽计数：PATH 预算是全局共享
     * 预算（默认每 tick 4 次），多女仆下常态紧张，属于环境噪声，不能作为
     * "该任务反复扫不完"的证据，否则会错误触发 exhaustedFallback。
     * PATH 耗尽时计数保持原样（不累加也不清零），直到一次完整扫描结束才复位。
     */
    public void recordScanOutcome(ResourceLocation taskUid, DetectionResult result, TaskScanCursor cursor) {
        String evidence = result.evidence();
        if (evidence.contains("BLOCK_BUDGET_EXHAUSTED")) {
            consecutiveBudgetExhaustions.merge(taskUid, 1, Integer::sum);
            return;
        }
        if (cursor == null && evidence.startsWith("FULL_SCAN_")) {
            completedScanCycles.merge(taskUid, 1, Integer::sum);
            consecutiveBudgetExhaustions.remove(taskUid);
        }
    }

    public boolean hasRepeatedBudgetExhaustion(ResourceLocation taskUid) {
        return consecutiveBudgetExhaustions.getOrDefault(taskUid, 0) >= 3;
    }

    public int completedScanCycles(ResourceLocation taskUid) {
        return completedScanCycles.getOrDefault(taskUid, 0);
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
        clearDetectionData();
        lastWarningTicks.clear();
        activeGeneration = 0;
        staleIgnoredCount = 0;
    }

    private void clearDetectionData() {
        latestAttempts.clear();
        definitiveResults.clear();
        cursors.clear();
        lastAttemptTicks.clear();
        lastDefinitiveAvailability.clear();
        definitiveConfirmations.clear();
        completedScanCycles.clear();
        consecutiveBudgetExhaustions.clear();
        lastDefinitiveUpdateTick = -1;
    }
}
