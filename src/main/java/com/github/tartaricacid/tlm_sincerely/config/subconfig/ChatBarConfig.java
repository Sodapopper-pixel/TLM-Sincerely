package com.github.tartaricacid.tlm_sincerely.config.subconfig;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 聊天栏配置。其中的 ChatModeEnabled / GlobalChatVisible 为 COMMON 配置，
 * 仅作为新玩家（无 /tlmchat mode|global 个人记录时）的默认值；
 * 玩家通过命令改写的是各自的运行时状态（见 {@link com.github.tartaricacid.tlm_sincerely.chatbar.ChatBarPlayerState}），
 * 管理员只能在此调整默认行为，其余条目仍为全服生效的服务端配置。
 */
public final class ChatBarConfig {
    public static ModConfigSpec.BooleanValue CHAT_MODE;
    public static ModConfigSpec.BooleanValue GLOBAL_VISIBLE;
    public static ModConfigSpec.BooleanValue REQUIRE_PREFIX;
    public static ModConfigSpec.DoubleValue AUTO_CHAT_RANGE;
    public static ModConfigSpec.ConfigValue<String> PREFIX_PATTERN;

    public static void init(ModConfigSpec.Builder builder) {
        builder.push("chatbar");

        builder.comment("Default maid chat mode for players without a personal /tlmchat mode setting");
        CHAT_MODE = builder.define("ChatModeEnabled", true);

        builder.comment("Default chat visibility for players without a personal /tlmchat global setting (global mode)");
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
