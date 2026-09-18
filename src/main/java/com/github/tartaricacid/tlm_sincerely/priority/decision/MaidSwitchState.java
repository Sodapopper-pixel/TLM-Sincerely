package com.github.tartaricacid.tlm_sincerely.priority.decision;

import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
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
    /** Latest tick brain activity (ATK/WLK/PATH/TGT) was observed for the current task. */
    private long busyObservedTick = -1;
    /**
     * First tick of the current continuous busy period. A sufficiently long
     * idle gap starts a new period so the hard ceiling applies independently
     * to later work instead of permanently disabling the guard.
     */
    private long busyStartTick = -1;
    /** Task the {@link #busyUnavailableSinceTick} counter belongs to; resets when the task changes. */
    private ResourceLocation busyTrackedTaskUid;
    /** First tick the tracked task was continuously reported UNAVAILABLE; -1 while not unavailable. */
    private long busyUnavailableSinceTick = -1;

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
        resetBusyGuard();
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

    /**
     * Records the latest tick the maid's brain showed active work for the
     * current task. Called every tick while brain activity is present, so the
     * guard always has fresh evidence even between decision ticks.
     */
    public void markBusyObserved(long currentTick, int idleForgiveTicks) {
        if (busyObservedTick < 0 || currentTick < busyObservedTick
                || currentTick - busyObservedTick > idleForgiveTicks) {
            busyStartTick = currentTick;
        }
        busyObservedTick = currentTick;
    }

    /**
     * True while brain activity was observed recently enough that the current
     * task can be considered "actually working". An idle brain (no activity
     * for {@code maxIdleTicks}) always releases the guard.
     */
    public boolean isBusyActive(long currentTick, int maxIdleTicks) {
        return busyObservedTick != -1 && currentTick >= busyObservedTick
                && currentTick - busyObservedTick <= maxIdleTicks;
    }

    /**
     * Tracks how long the current task has been continuously UNAVAILABLE.
     * The counter resets whenever the task changes or becomes AVAILABLE;
     * UNKNOWN and EXPIRED results keep the existing timer so the guard
     * does not lose evidence.
     */
    public void trackCurrentAvailability(ResourceLocation taskUid, Availability availability, long currentTick) {
        if (taskUid == null) {
            return;
        }
        if (availability == Availability.AVAILABLE) {
            busyTrackedTaskUid = null;
            busyUnavailableSinceTick = -1;
            return;
        }
        if (availability == Availability.UNKNOWN) {
            // UNKNOWN/EXPIRED may bridge two definitive UNAVAILABLE samples,
            // but it must never create an unavailable period by itself.
            if (busyTrackedTaskUid != null && !taskUid.equals(busyTrackedTaskUid)) {
                busyTrackedTaskUid = null;
                busyUnavailableSinceTick = -1;
            }
            return;
        }
        if (!taskUid.equals(busyTrackedTaskUid) || busyUnavailableSinceTick < 0) {
            busyTrackedTaskUid = taskUid;
            busyUnavailableSinceTick = currentTick;
        }
    }

    /**
     * True once the current task has been continuously UNAVAILABLE for at
     * least {@code holdTicks}; with {@code holdTicks = 0} this is always true
     * while the task is UNAVAILABLE, releasing the guard immediately.
     */
    public boolean isUnavailableHoldElapsed(long currentTick, int holdTicks) {
        return busyUnavailableSinceTick != -1 && currentTick >= busyUnavailableSinceTick
                && currentTick - busyUnavailableSinceTick >= holdTicks;
    }

    public long busyObservedTick() {
        return busyObservedTick;
    }

    public long busyStartTick() {
        return busyStartTick;
    }

    public long busyUnavailableSinceTick() {
        return busyUnavailableSinceTick;
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
        resetBusyGuard();
        lastSwitchReason = "NONE";
        clearAttackPreempt();
    }

    private void resetBusyGuard() {
        busyObservedTick = -1;
        busyStartTick = -1;
        busyTrackedTaskUid = null;
        busyUnavailableSinceTick = -1;
    }

    private void clearReverseTracking() {
        lastNormalFromTaskUid = null;
        lastNormalToTaskUid = null;
        reverseSwitchCount = 0;
        reverseCooldownEndTick = Long.MIN_VALUE;
    }
}
