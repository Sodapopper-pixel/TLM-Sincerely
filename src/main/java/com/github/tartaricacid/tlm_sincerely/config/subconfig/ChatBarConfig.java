package com.github.tartaricacid.tlm_sincerely.config.subconfig;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ChatBarConfig {
    public static ModConfigSpec.BooleanValue CHAT_MODE;
    public static ModConfigSpec.BooleanValue GLOBAL_VISIBLE;
    public static ModConfigSpec.BooleanValue REQUIRE_PREFIX;
    public static ModConfigSpec.DoubleValue AUTO_CHAT_RANGE;
    public static ModConfigSpec.ConfigValue<String> PREFIX_PATTERN;

    public static void init(ModConfigSpec.Builder builder) {
        builder.push("chatbar");

        builder.comment("Chat with maid mode (button toggle state, enabled by default)");
        CHAT_MODE = builder.define("ChatModeEnabled", true);

        builder.comment("Whether chat messages are visible to all players (global mode)");
        GLOBAL_VISIBLE = builder.define("GlobalChatVisible", true);

        builder.comment("Whether to require @ prefix for maid chat");
        REQUIRE_PREFIX = builder.define("RequirePrefix", true);

        builder.comment("Auto chat range when no prefix (0 = disabled)");
        AUTO_CHAT_RANGE = builder.defineInRange("AutoChatRange", 5.0, 0.0, 64.0);

        builder.comment("Prefix pattern for specifying maid name");
        PREFIX_PATTERN = builder.define("PrefixPattern", "@");

        builder.pop();
    }
}
