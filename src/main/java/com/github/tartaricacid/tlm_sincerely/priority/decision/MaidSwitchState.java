package com.github.tartaricacid.tlm_sincerely.priority.decision;

import net.minecraft.resources.ResourceLocation;

public final class MaidSwitchState {
    private long lastSwitchTick = Long.MIN_VALUE;
    private ResourceLocation suspendedTaskUid;
    private boolean attackPreempted;
    private long attackMissingSinceTick = -1;
    private int startupMemoryMask = Integer.MIN_VALUE;
    private boolean startupBusyObserved;
    private boolean forcedBrainRefreshDone;
    private ResourceLocation lastNormalFromTaskUid;
    private ResourceLocation lastNormalToTaskUid;
    private String lastSwitchReason = "NONE";
    private int reverseSwitchCount;
    private long reverseCooldownEndTick = Long.MIN_VALUE;

    public boolean canSwitchNormally(long currentTick, int minimumHoldTicks) {
        return lastSwitchTick == Long.MIN_VALUE || currentTick < lastSwitchTick
                || currentTick - lastSwitchTick >= minimumHoldTicks;
    }

    public void recordSwitch(long currentTick, ResourceLocation fromTaskUid, ResourceLocation toTaskUid,
                             String reason, boolean normalSwitch, int reverseWindowTicks) {
        long previousSwitchTick = lastSwitchTick;
        lastSwitchTick = currentTick;
        startupMemoryMask = Integer.MIN_VALUE;
        startupBusyObserved = false;
        forcedBrainRefreshDone = false;
        lastSwitchReason = reason;
        if (!normalSwitch) {
            clearReverseTracking();
            return;
        }
        boolean reverse = lastNormalFromTaskUid != null && lastNormalToTaskUid != null
                && fromTaskUid.equals(lastNormalToTaskUid) && toTaskUid.equals(lastNormalFromTaskUid)
                && previousSwitchTick != Long.MIN_VALUE && currentTick >= previousSwitchTick
                && currentTick - previousSwitchTick <= reverseWindowTicks;
        reverseSwitchCount = reverse ? reverseSwitchCount + 1 : 0;
        lastNormalFromTaskUid = fromTaskUid;
        lastNormalToTaskUid = toTaskUid;
    }

    public long lastSwitchTick() {
        return lastSwitchTick;
    }

    public String lastSwitchReason() {
        return lastSwitchReason;
    }

    public boolean isReverseCooldownActive(long currentTick) {
        if (reverseCooldownEndTick == Long.MIN_VALUE || currentTick >= reverseCooldownEndTick) {
            if (reverseCooldownEndTick != Long.MIN_VALUE && currentTick >= reverseCooldownEndTick) {
                reverseCooldownEndTick = Long.MIN_VALUE;
                reverseSwitchCount = 0;
            }
            return false;
        }
        return true;
    }

    /**
     * Starts a finite cooldown before a normal switch would complete the same
     * A-to-B-to-A reversal pair too many times.
     */
    public boolean shouldSuppressReverseSwitch(ResourceLocation fromTaskUid, ResourceLocation toTaskUid,
                                                long currentTick, int reverseWindowTicks, int threshold,
                                                int cooldownTicks) {
        if (isReverseCooldownActive(currentTick)) {
            return true;
        }
        boolean reverse = lastNormalFromTaskUid != null && lastNormalToTaskUid != null
                && fromTaskUid.equals(lastNormalToTaskUid) && toTaskUid.equals(lastNormalFromTaskUid)
                && lastSwitchTick != Long.MIN_VALUE && currentTick >= lastSwitchTick
                && currentTick - lastSwitchTick <= reverseWindowTicks;
        if (!reverse || reverseSwitchCount + 1 < threshold) {
            return false;
        }
        reverseCooldownEndTick = currentTick + cooldownTicks;
        reverseSwitchCount = 0;
        return true;
    }

    public int reverseSwitchCount() {
        return reverseSwitchCount;
    }

    public long reverseCooldownEndTick() {
        return reverseCooldownEndTick;
    }

    public int startupMemoryMask() {
        return startupMemoryMask;
    }

    public void setStartupMemoryMask(int startupMemoryMask) {
        this.startupMemoryMask = startupMemoryMask;
    }

    public boolean startupBusyObserved() {
        return startupBusyObserved;
    }

    public void markStartupBusyObserved() {
        startupBusyObserved = true;
    }

    public boolean forcedBrainRefreshDone() {
        return forcedBrainRefreshDone;
    }

    public void markForcedBrainRefreshDone() {
        forcedBrainRefreshDone = true;
        startupMemoryMask = Integer.MIN_VALUE;
        startupBusyObserved = false;
    }

    public void beginAttackPreempt(ResourceLocation taskUid) {
        if (!attackPreempted) {
            suspendedTaskUid = taskUid;
            attackPreempted = true;
        }
        attackMissingSinceTick = -1;
    }

    public boolean isAttackPreempted() {
        return attackPreempted;
    }

    public ResourceLocation suspendedTaskUid() {
        return suspendedTaskUid;
    }

    public long attackMissingSinceTick() {
        return attackMissingSinceTick;
    }

    public void markAttackMissing(long currentTick) {
        if (attackMissingSinceTick < 0 || currentTick < attackMissingSinceTick) {
            attackMissingSinceTick = currentTick;
        }
    }

    public void markAttackPresent() {
        attackMissingSinceTick = -1;
    }

    public void clearAttackPreempt() {
        suspendedTaskUid = null;
        attackPreempted = false;
        attackMissingSinceTick = -1;
    }

    public void resetForTickRegression() {
        lastSwitchTick = Long.MIN_VALUE;
        startupMemoryMask = Integer.MIN_VALUE;
        startupBusyObserved = false;
        forcedBrainRefreshDone = false;
        clearReverseTracking();
        lastSwitchReason = "NONE";
        clearAttackPreempt();
    }

    private void clearReverseTracking() {
        lastNormalFromTaskUid = null;
        lastNormalToTaskUid = null;
        reverseSwitchCount = 0;
        reverseCooldownEndTick = Long.MIN_VALUE;
    }
}
