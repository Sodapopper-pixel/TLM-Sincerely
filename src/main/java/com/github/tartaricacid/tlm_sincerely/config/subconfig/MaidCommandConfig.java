package com.github.tartaricacid.tlm_sincerely.config.subconfig;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class MaidCommandConfig {
    public static final List<String> DEFAULT_CONFIRMATION_REQUIRED_COMMANDS = List.of(
            "kill", "fill", "setblock", "clone", "clear", "datapack", "function", "reload", "stop",
            "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip", "kick", "whitelist",
            "gamerule", "difficulty", "setworldspawn", "worldborder", "forceload", "spreadplayers",
            "save-off", "save-on"
    );

    /**
     * Loop-back commands of this addon and the maid mod itself. They are hard
     * blocked because a command that can start a new AI conversation would let
     * the model recurse into itself.
     */
    public static final List<String> DEFAULT_BLACKLISTED_COMMANDS = List.of(
            "tlmchat", "tlmmemory", "tlmautowork", "tlm", "tlmconfirm"
    );

    public static ForgeConfigSpec.BooleanValue COMMAND_TOOL_ENABLED;
    public static ForgeConfigSpec.BooleanValue CONFIRMATION_ENABLED;
    public static ForgeConfigSpec.BooleanValue SESSION_CONFIRMATION_ENABLED;
    public static ForgeConfigSpec.IntValue CONFIRMATION_TIMEOUT_SECONDS;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> CONFIRMATION_REQUIRED_COMMANDS;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> BLACKLISTED_COMMANDS;
    public static ForgeConfigSpec.IntValue MAX_PERMISSION_LEVEL;
    public static ForgeConfigSpec.IntValue MAX_COMMANDS_PER_REQUEST;
    public static ForgeConfigSpec.IntValue MAX_COMMAND_LENGTH;
    public static ForgeConfigSpec.IntValue TOOL_RESULT_MAX_CHARS;
    public static ForgeConfigSpec.IntValue AUDIT_LOG_MAX_SIZE_MB;

    public static void init(ForgeConfigSpec.Builder builder) {
        builder.push("maid_command");

        builder.comment("Expose the run_command AI tool to the maid (she executes Minecraft commands as the owner)");
        COMMAND_TOOL_ENABLED = builder.define("CommandToolEnabled", true);

        builder.comment("Require the owner to click a confirmation button before destructive commands (from ConfirmationRequiredCommands) run");
        CONFIRMATION_ENABLED = builder.define("ConfirmationEnabled", false);

        builder.comment("Allow the '[do not ask again this session]' button; the trust set is kept in memory and reset on owner logout");
        SESSION_CONFIRMATION_ENABLED = builder.define("SessionConfirmationEnabled", true);

        builder.comment("Seconds before a pending command confirmation is cancelled");
        CONFIRMATION_TIMEOUT_SECONDS = builder.defineInRange("ConfirmationTimeoutSeconds", 60, 10, 600);

        builder.comment("Root command names that require owner confirmation (when ConfirmationEnabled is true)");
        CONFIRMATION_REQUIRED_COMMANDS = builder.defineList("ConfirmationRequiredCommands",
                DEFAULT_CONFIRMATION_REQUIRED_COMMANDS, value -> value instanceof String);

        builder.comment("Root command names that are always refused and never reach the confirmation flow");
        BLACKLISTED_COMMANDS = builder.defineList("BlacklistedCommands",
                DEFAULT_BLACKLISTED_COMMANDS, value -> value instanceof String);

        builder.comment("Upper bound for the owner's permission level; it can only lower the owner's real level, never raise it");
        MAX_PERMISSION_LEVEL = builder.defineInRange("MaxPermissionLevel", 4, 0, 4);

        builder.comment("Maximum command attempts per conversation request chain");
        MAX_COMMANDS_PER_REQUEST = builder.defineInRange("MaxCommandsPerRequest", 3, 1, 16);

        builder.comment("Maximum accepted command text length in characters");
        MAX_COMMAND_LENGTH = builder.defineInRange("MaxCommandLength", 1024, 16, 4096);

        builder.comment("Maximum command output length returned to the LLM (longer output is truncated)");
        TOOL_RESULT_MAX_CHARS = builder.defineInRange("ToolResultMaxChars", 2000, 200, 20000);

        builder.comment("Audit log size limit in MB before command_audit.log is rotated to command_audit.log.1");
        AUDIT_LOG_MAX_SIZE_MB = builder.defineInRange("AuditLogMaxSizeMb", 16, 1, 1024);

        builder.pop();
    }
}
