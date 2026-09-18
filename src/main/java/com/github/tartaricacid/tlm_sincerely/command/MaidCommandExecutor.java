package com.github.tartaricacid.tlm_sincerely.command;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.MaidCommandConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Executes a command with the owner's identity and collects its output.
 *
 * <p>The source stack comes from {@code owner.createCommandSourceStack()}, so
 * position, dimension, {@code @s}, relative coordinates and permission level
 * all belong to the owner. The permission cap can only lower that level. A
 * {@link CommandRecorder} replaces the output sink without touching identity.
 */
public final class MaidCommandExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger("TLM_Sincerely/MaidCommand");

    private MaidCommandExecutor() {
    }

    public record Outcome(int resultCode, String text, boolean error) {
    }

    public static Outcome execute(MinecraftServer server, ServerPlayer owner, String command) {
        CommandRecorder recorder = new CommandRecorder();
        CommandSourceStack stack = owner.createCommandSourceStack().withSource(recorder);

        int cap = MaidCommandConfig.MAX_PERMISSION_LEVEL.get();
        if (cap < 4 && stack.hasPermission(cap + 1)) {
            // withPermission sets the level exactly; it is only reached when
            // the owner is above the configured cap, so this can never raise
            // the owner's real level.
            stack = stack.withPermission(cap);
        }

        int resultCode = 0;
        try {
            resultCode = server.getCommands().performPrefixedCommand(stack, command);
        } catch (Throwable throwable) {
            // Vanilla already converts CommandSyntaxException and other
            // runtime exceptions into failure text; this catches cancelled
            // Forge CommandEvent handlers and unchecked errors from mod
            // commands that would otherwise break the conversation.
            String detail = throwable.getMessage() == null
                    ? throwable.getClass().getSimpleName() : throwable.getMessage();
            LOGGER.warn("Command '/{}' raised an unexpected error: {}", command, detail, throwable);
            return new Outcome(0, truncate("Command execution failed with an unexpected error: " + detail), true);
        }

        String text = joinMessages(recorder.messages());
        if (text.isEmpty()) {
            text = "Result code: " + resultCode;
        }
        return new Outcome(resultCode, truncate(text), false);
    }

    /**
     * Executes a command, writes the audit entry, sends the owner receipt and
     * returns the text that is handed back to the model.
     */
    public static String runAndReport(EntityMaid maid, ServerPlayer owner, String command,
                                      String hitName, CommandAuditLog.Decision decision) {
        MinecraftServer server = owner.getServer();
        Outcome outcome = execute(server, owner, command);

        CommandAuditLog.Decision auditDecision = outcome.error()
                ? CommandAuditLog.Decision.ERROR : decision;
        CommandAuditLog.log(maid, owner, auditDecision, outcome.resultCode(), command, hitName);
        sendEcho(owner, maid, command, outcome);
        return outcome.text();
    }

    private static void sendEcho(ServerPlayer owner, EntityMaid maid, String command, Outcome outcome) {
        Component receipt = Component.translatable("command.tlm_sincerely.run_command.echo",
                maid.getName().getString(), sanitizeForDisplay(command), String.valueOf(outcome.resultCode()))
                .withStyle(outcome.error() ? ChatFormatting.RED : ChatFormatting.GRAY);
        owner.sendSystemMessage(receipt);

        String preview = summarize(sanitizeForDisplay(outcome.text()));
        if (!preview.isEmpty()) {
            owner.sendSystemMessage(Component.translatable(
                    "command.tlm_sincerely.run_command.echo_output", preview
            ).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /**
     * Strips paragraph-sign formatting codes and control characters from text
     * that is rendered into chat components (the model can put arbitrary text
     * into a command, and display text must not be able to spoof formatting).
     */
    public static String sanitizeForDisplay(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\u00a7') {
                i++;
                continue;
            }
            if (c < 0x20 && c != '\n') {
                continue;
            }
            builder.append(c);
        }
        return builder.toString();
    }

    /** One-line preview for the owner receipt; the full output only goes to the model. */
    private static String summarize(String text) {
        String singleLine = text.replace('\n', ' ').trim();
        if (singleLine.length() > 200) {
            singleLine = singleLine.substring(0, 200) + "...";
        }
        return singleLine;
    }

    private static String joinMessages(List<Component> messages) {
        // Stop collecting once the model budget is exceeded: commands such as
        // /data get can produce very large output and the rest is truncated.
        int budget = MaidCommandConfig.TOOL_RESULT_MAX_CHARS.get() + 64;
        StringBuilder builder = new StringBuilder();
        boolean lastBlank = false;
        outer:
        for (Component message : messages) {
            for (String line : message.getString().split("\n", -1)) {
                if (builder.length() >= budget) {
                    break outer;
                }
                boolean blank = line.isBlank();
                if (blank && lastBlank) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
                lastBlank = blank;
            }
        }
        return builder.toString().trim();
    }

    private static String truncate(String text) {
        int max = MaidCommandConfig.TOOL_RESULT_MAX_CHARS.get();
        if (text.length() <= max) {
            return text;
        }
        String suffix = "\n...[output truncated]";
        int keep = Math.max(0, max - suffix.length());
        return text.substring(0, keep) + suffix;
    }
}
