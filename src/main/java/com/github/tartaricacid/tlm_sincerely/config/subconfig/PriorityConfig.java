package com.github.tartaricacid.tlm_sincerely.config.subconfig;

import net.minecraftforge.common.ForgeConfigSpec;

public final class PriorityConfig {
    public static ForgeConfigSpec.BooleanValue ENABLED;
    public static ForgeConfigSpec.IntValue COOLDOWN;

    public static void init(ForgeConfigSpec.Builder builder) {
        builder.push("multi_task");

        builder.comment("Enable multi-task mode for maids");
        ENABLED = builder.define("Enabled", false);

        builder.comment("Polling interval in ticks for task switching (20 ticks = 1 second)");
        COOLDOWN = builder.defineInRange("PollInterval", 100, 20, 6000);

        builder.pop();
    }
}
