package com.github.tartaricacid.tlm_sincerely.config.subconfig;

import net.minecraftforge.common.ForgeConfigSpec;

public final class ChatBarConfig {
    public static ForgeConfigSpec.BooleanValue BUTTON_ENABLED;
    public static ForgeConfigSpec.BooleanValue CHAT_MODE;
    public static ForgeConfigSpec.BooleanValue GLOBAL_VISIBLE;
    public static ForgeConfigSpec.BooleanValue REQUIRE_PREFIX;
    public static ForgeConfigSpec.DoubleValue AUTO_CHAT_RANGE;
    public static ForgeConfigSpec.ConfigValue<String> PREFIX_PATTERN;

    public static void init(ForgeConfigSpec.Builder builder) {
        builder.push("chatbar");

        builder.comment("Whether to enable chat bar quick buttons");
        BUTTON_ENABLED = builder.define("ButtonEnabled", true);

        builder.comment("Chat with maid mode (button toggle state, default off)");
        CHAT_MODE = builder.define("ChatModeEnabled", false);

        builder.comment("Whether chat messages are visible to all players (global mode)");
        GLOBAL_VISIBLE = builder.define("GlobalChatVisible", true);

        builder.comment("Whether to require @ prefix for maid chat");
        REQUIRE_PREFIX = builder.define("RequirePrefix", false);

        builder.comment("Auto chat range when no prefix (0 = disabled)");
        AUTO_CHAT_RANGE = builder.defineInRange("AutoChatRange", 5.0, 0.0, 64.0);

        builder.comment("Prefix pattern for specifying maid name");
        PREFIX_PATTERN = builder.define("PrefixPattern", "@");

        builder.pop();
    }
}
