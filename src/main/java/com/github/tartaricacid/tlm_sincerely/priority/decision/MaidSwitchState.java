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

    public boolean canSwitchNormally(long currentTick, int minimumHoldTicks) {
        return lastSwitchTick == Long.MIN_VALUE || currentTick < lastSwitchTick
                || currentTick - lastSwitchTick >= minimumHoldTicks;
    }

    public void recordSwitch(long currentTick) {
        lastSwitchTick = currentTick;
        startupMemoryMask = Integer.MIN_VALUE;
        startupBusyObserved = false;
        forcedBrainRefreshDone = false;
    }

    public long lastSwitchTick() {
        return lastSwitchTick;
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
        clearAttackPreempt();
    }
}
