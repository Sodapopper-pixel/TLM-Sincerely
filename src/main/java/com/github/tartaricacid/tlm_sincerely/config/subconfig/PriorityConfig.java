package com.github.tartaricacid.tlm_sincerely.config.subconfig;

import net.minecraftforge.common.ForgeConfigSpec;

public final class PriorityConfig {
    public static ForgeConfigSpec.BooleanValue ENABLED;
    public static ForgeConfigSpec.IntValue COOLDOWN;
    public static ForgeConfigSpec.BooleanValue EXPERIMENTAL_ATTACK_PREEMPT;
    public static ForgeConfigSpec.BooleanValue FORCE_BRAIN_REFRESH_ON_STUCK;
    public static ForgeConfigSpec.IntValue AVAILABLE_CONFIRMATIONS;
    public static ForgeConfigSpec.IntValue UNAVAILABLE_CONFIRMATIONS;
    public static ForgeConfigSpec.IntValue MINIMUM_TASK_HOLD_TICKS;
    public static ForgeConfigSpec.IntValue DETECTION_BLOCK_BUDGET_PER_TICK;
    public static ForgeConfigSpec.IntValue PATH_CHECK_BUDGET_PER_TICK;

    public static void init(ForgeConfigSpec.Builder builder) {
        builder.push("multi_task");

        builder.comment("Enable multi-task mode for maids");
        ENABLED = builder.define("Enabled", false);

        builder.comment("Polling interval in ticks for task switching (20 ticks = 1 second)");
        COOLDOWN = builder.defineInRange("PollInterval", 100, 20, 6000);

        builder.comment("Experimental: immediately preempt normal hold time for configured attack tasks");
        EXPERIMENTAL_ATTACK_PREEMPT = builder.define("ExperimentalAttackPreempt", false);

        builder.comment("Force one Brain refresh when an automatically switched task remains available but has no work target after 60 ticks");
        FORCE_BRAIN_REFRESH_ON_STUCK = builder.define("ForceBrainRefreshOnStuck", true);

        builder.comment("Consecutive AVAILABLE samples required before switching to a task");
        AVAILABLE_CONFIRMATIONS = builder.defineInRange("AvailableConfirmations", 1, 1, 20);

        builder.comment("Consecutive UNAVAILABLE samples required before leaving the current task");
        UNAVAILABLE_CONFIRMATIONS = builder.defineInRange("UnavailableConfirmations", 2, 1, 20);

        builder.comment("Minimum ticks to keep a normally selected task before normal switching");
        MINIMUM_TASK_HOLD_TICKS = builder.defineInRange("MinimumTaskHoldTicks", 60, 0, 12000);

        builder.comment("Maximum farm block checks performed by all maids per server tick");
        DETECTION_BLOCK_BUDGET_PER_TICK = builder.defineInRange("DetectionBlockBudgetPerTick", 256, 16, 4096);

        builder.comment("Maximum path reachability checks performed by all maids per server tick");
        PATH_CHECK_BUDGET_PER_TICK = builder.defineInRange("PathCheckBudgetPerTick", 4, 1, 128);

        builder.pop();
    }
}
