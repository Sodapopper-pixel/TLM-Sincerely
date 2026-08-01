package com.github.tartaricacid.tlm_sincerely.config.subconfig;

import net.minecraftforge.common.ForgeConfigSpec;

public final class PriorityConfig {
    public static ForgeConfigSpec.BooleanValue ENABLED;
    public static ForgeConfigSpec.IntValue COOLDOWN;
    public static ForgeConfigSpec.BooleanValue ATTACK_PREEMPT;
    public static ForgeConfigSpec.IntValue PROBE_GRACE_TICKS;
    public static ForgeConfigSpec.IntValue PROBE_WAIT_TICKS;
    public static ForgeConfigSpec.IntValue PROBE_COOLDOWN;

    public static void init(ForgeConfigSpec.Builder builder) {
        builder.push("multi_task");

        builder.comment("Enable multi-task mode for maids");
        ENABLED = builder.define("Enabled", false);

        builder.comment("Polling interval in ticks for task switching (20 ticks = 1 second)");
        COOLDOWN = builder.defineInRange("PollInterval", 100, 20, 6000);

        builder.comment("Attack tasks always preempt lower priority tasks when targets are available");
        ATTACK_PREEMPT = builder.define("AttackPreempt", true);

        builder.comment("Consecutive idle ticks before triggering a probe scan (1 tick = 0.05s)");
        PROBE_GRACE_TICKS = builder.defineInRange("ProbeGraceTicks", 40, 5, 200);

        builder.comment("Ticks to wait after a probe switch for brain to populate results");
        PROBE_WAIT_TICKS = builder.defineInRange("ProbeWaitTicks", 10, 1, 40);

        builder.comment("Cooldown in ticks before re-probing when all tasks are idle");
        PROBE_COOLDOWN = builder.defineInRange("ProbeCooldown", 200, 20, 6000);

        builder.pop();
    }
}
