package com.github.tartaricacid.tlm_sincerely.config.subconfig;

import net.minecraftforge.common.ForgeConfigSpec;

public final class PriorityConfig {
    public static ForgeConfigSpec.BooleanValue ENABLED;
    public static ForgeConfigSpec.IntValue COOLDOWN;
    public static ForgeConfigSpec.BooleanValue EXPERIMENTAL_ATTACK_PREEMPT;
    public static ForgeConfigSpec.BooleanValue FORCE_ENABLE_PRESELECTED;
    public static ForgeConfigSpec.BooleanValue DISABLE_COMPAT_REMINDER;
    public static ForgeConfigSpec.BooleanValue FORCE_BRAIN_REFRESH_ON_STUCK;
    public static ForgeConfigSpec.IntValue AVAILABLE_CONFIRMATIONS;
    public static ForgeConfigSpec.IntValue UNAVAILABLE_CONFIRMATIONS;
    public static ForgeConfigSpec.IntValue MINIMUM_TASK_HOLD_TICKS;
    public static ForgeConfigSpec.IntValue REVERSE_SWITCH_WINDOW_TICKS;
    public static ForgeConfigSpec.IntValue REVERSE_SWITCH_THRESHOLD;
    public static ForgeConfigSpec.IntValue REVERSE_SWITCH_COOLDOWN_TICKS;
    public static ForgeConfigSpec.BooleanValue BUSY_GUARD_ENABLED;
    public static ForgeConfigSpec.IntValue BUSY_IDLE_FORGIVE_TICKS;
    public static ForgeConfigSpec.IntValue BUSY_UNAVAILABLE_HOLD_TICKS;
    public static ForgeConfigSpec.IntValue BUSY_GUARD_MAX_TICKS;
    public static ForgeConfigSpec.IntValue DETECTION_BLOCK_BUDGET_PER_TICK;
    public static ForgeConfigSpec.IntValue PATH_CHECK_BUDGET_PER_TICK;

    public static void init(ForgeConfigSpec.Builder builder) {
        builder.push("multi_task");

        builder.comment(
                "Global switch for the auto work switch (auto task switching) feature. Enabled by default.",
                "Disabling it only pauses automatic scheduling — real tasks (the maid's current Task) and",
                "per-maid detection state / scan cursors are preserved, so re-enabling resumes where it left off.");
        ENABLED = builder.define("Enabled", true);

        builder.comment("Polling interval in ticks for task switching (20 ticks = 1 second)");
        COOLDOWN = builder.defineInRange("PollInterval", 100, 20, 6000);

        builder.comment("Experimental: immediately preempt normal hold time for configured attack tasks");
        EXPERIMENTAL_ATTACK_PREEMPT = builder.define("ExperimentalAttackPreempt", false);

        builder.comment(
                "Experimental: bypass the compatibility blacklist when choosing tasks to switch into.",
                "A detector must still report AVAILABLE; idle and tasks without a detector never switch.",
                "The compatibility report keeps listing blacklisted tasks as BLOCKED.");
        FORCE_ENABLE_PRESELECTED = builder.define("ForceEnablePreselected", false);

        builder.comment(
                "Suppress the chat login reminder about work compatibility problems.",
                "Off by default, so the existing reminder level keeps applying. Turning it on mutes those",
                "reminders entirely; /tlmautowork compat report still works manually, and the",
                "force-enable-preselected warning is not affected.");
        DISABLE_COMPAT_REMINDER = builder.define("DisableCompatReminder", false);

        builder.comment("Force one Brain refresh when an automatically switched task remains available but has no work target after 60 ticks");
        FORCE_BRAIN_REFRESH_ON_STUCK = builder.define("ForceBrainRefreshOnStuck", true);

        builder.comment("Consecutive AVAILABLE samples required before switching to a task");
        AVAILABLE_CONFIRMATIONS = builder.defineInRange("AvailableConfirmations", 1, 1, 20);

        builder.comment("Consecutive UNAVAILABLE samples required before leaving the current task");
        UNAVAILABLE_CONFIRMATIONS = builder.defineInRange("UnavailableConfirmations", 2, 1, 20);

        builder.comment("Minimum ticks to keep a normally selected task before normal switching");
        MINIMUM_TASK_HOLD_TICKS = builder.defineInRange("MinimumTaskHoldTicks", 60, 0, 12000);

        builder.comment("Time window used to count normal A-to-B-to-A task reversals");
        REVERSE_SWITCH_WINDOW_TICKS = builder.defineInRange("ReverseSwitchWindowTicks", 240, 20, 12000);

        builder.comment("Normal reverse switches allowed inside the window before an anti-thrashing cooldown starts");
        REVERSE_SWITCH_THRESHOLD = builder.defineInRange("ReverseSwitchThreshold", 2, 1, 20);

        builder.comment("Cooldown applied after repeated normal reverse switches; experimental attack preemption is excluded");
        REVERSE_SWITCH_COOLDOWN_TICKS = builder.defineInRange("ReverseSwitchCooldownTicks", 200, 20, 12000);

        builder.comment(
                "Busy guard: while the maid's brain shows active work for the current task",
                "(ATTACK_TARGET / WALK_TARGET / PATH / TARGET_POS), normal switching is suppressed so an",
                "in-progress job (e.g. felling a tree) is not interrupted by other work.",
                "Experimental attack preemption runs on a separate path and is never blocked by this guard.");
        BUSY_GUARD_ENABLED = builder.define("BusyGuardEnabled", true);

        builder.comment("Brain activity older than this many ticks no longer counts as busy; the guard then releases the task (3 s default)");
        BUSY_IDLE_FORGIVE_TICKS = builder.defineInRange("BusyIdleForgiveTicks", 60, 10, 1200);

        builder.comment("Once the current task is confirmed UNAVAILABLE, extra hold ticks the busy guard keeps it before letting it switch away (4 s default)");
        BUSY_UNAVAILABLE_HOLD_TICKS = builder.defineInRange("BusyUnavailableHoldTicks", 80, 0, 1200);

        builder.comment("Hard ceiling: one continuous busy period never blocks normal switching longer than this, even if the brain stays active (30 s default)");
        BUSY_GUARD_MAX_TICKS = builder.defineInRange("BusyGuardMaxTicks", 600, 60, 12000);

        builder.comment("Maximum farm block checks performed by all maids per server tick");
        DETECTION_BLOCK_BUDGET_PER_TICK = builder.defineInRange("DetectionBlockBudgetPerTick", 256, 16, 4096);

        builder.comment("Maximum path reachability checks performed by all maids per server tick");
        PATH_CHECK_BUDGET_PER_TICK = builder.defineInRange("PathCheckBudgetPerTick", 4, 1, 128);

        builder.pop();
    }
}
