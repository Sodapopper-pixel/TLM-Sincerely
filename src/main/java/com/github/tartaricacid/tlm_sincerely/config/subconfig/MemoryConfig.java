package com.github.tartaricacid.tlm_sincerely.config.subconfig;

import net.minecraftforge.common.ForgeConfigSpec;

public final class MemoryConfig {
    public static ForgeConfigSpec.BooleanValue ENABLED;
    public static ForgeConfigSpec.IntValue MAX_MEMORIES;
    public static ForgeConfigSpec.IntValue CORE_LIMIT;
    public static ForgeConfigSpec.IntValue CONTEXT_PREVIEW_LENGTH;

    public static void init(ForgeConfigSpec.Builder builder) {
        builder.push("memory");

        builder.comment("Enable the maid memory system");
        ENABLED = builder.define("Enabled", true);

        builder.comment("Maximum number of memories per maid");
        MAX_MEMORIES = builder.defineInRange("MaxMemories", 50, 1, 200);

        builder.comment("Maximum number of core memories injected in full text (0 = all archive preview only)");
        CORE_LIMIT = builder.defineInRange("CoreMemoryLimit", 10, 0, 50);

        builder.comment("Truncation length for archive memory values in context preview");
        CONTEXT_PREVIEW_LENGTH = builder.defineInRange("ContextPreviewLength", 30, 10, 200);

        builder.pop();
    }
}
