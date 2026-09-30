package com.github.tartaricacid.tlm_sincerely.config.subconfig;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.List;

public final class MemoryConfig {
    public static ForgeConfigSpec.BooleanValue ENABLED;
    public static ForgeConfigSpec.IntValue MAX_MEMORIES;
    public static ForgeConfigSpec.IntValue CORE_LIMIT;
    public static ForgeConfigSpec.IntValue CONTEXT_PREVIEW_LENGTH;
    public static ForgeConfigSpec.BooleanValue AUTO_EVICT;
    public static ForgeConfigSpec.BooleanValue MEMORY_GUIDANCE;
    public static ForgeConfigSpec.BooleanValue TIDY_ENABLED;
    public static ForgeConfigSpec.DoubleValue TIDY_THRESHOLD;
    public static ForgeConfigSpec.IntValue TIDY_COOLDOWN_MINUTES;
    public static ForgeConfigSpec.BooleanValue SHOW_SOURCE;
    public static ForgeConfigSpec.ConfigValue<String> PREVIEW_MODE;

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

        builder.comment("Automatically evict the oldest archive memory when capacity is full");
        AUTO_EVICT = builder.define("AutoEvict", true);

        builder.comment("Inject memory guidance as a system message in LLM requests");
        MEMORY_GUIDANCE = builder.define("MemoryGuidance", true);

        builder.comment("Enable automatic memory maintenance (merge similar archive entries)");
        TIDY_ENABLED = builder.define("TidyEnabled", true);

        builder.comment("Threshold ratio of memory capacity to trigger maintenance (0.5-1.0)");
        TIDY_THRESHOLD = builder.defineInRange("TidyThreshold", 0.8, 0.5, 1.0);

        builder.comment("Cooldown in minutes between maintenance runs");
        TIDY_COOLDOWN_MINUTES = builder.defineInRange("TidyCooldownMinutes", 20, 1, 1440);

        builder.comment("Show memory source (player name) in context preview and recall");
        SHOW_SOURCE = builder.define("ShowSource", false);

        builder.comment("Preview mode for archive memories: 'full' (key + truncated value) or 'keys_only' (key only)");
        // allowedValues 必须是可变 List：Forge ModConfigSpec.correct 对缺失键以 null 调用校验器，
        // 而 JDK 不可变 List.of(...).contains(null) 会抛 NPE（首次创建配置文件时静默炸掉整个加载流程，
        // 连锁触发资源包清空——游戏文本回退英文——以及创建世界时崩溃）
        PREVIEW_MODE = builder.defineInList("PreviewMode", "full", new ArrayList<>(List.of("full", "keys_only")));

        builder.pop();
    }
}
