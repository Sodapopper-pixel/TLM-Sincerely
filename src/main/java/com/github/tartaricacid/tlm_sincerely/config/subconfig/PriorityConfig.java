package com.github.tartaricacid.tlm_sincerely.config.subconfig;

import net.minecraftforge.common.ForgeConfigSpec;

public final class PriorityConfig {
    public static ForgeConfigSpec.BooleanValue ENABLED;
    public static ForgeConfigSpec.IntValue COOLDOWN;

    public static void init(ForgeConfigSpec.Builder builder) {
        builder.push("priority");

        builder.comment("Enable automatic task switching based on priority");
        ENABLED = builder.define("Enabled", true);

        builder.comment("Cooldown in ticks between auto-switches (20 ticks = 1 second)");
        COOLDOWN = builder.defineInRange("Cooldown", 100, 20, 6000);

        builder.pop();
    }
}
