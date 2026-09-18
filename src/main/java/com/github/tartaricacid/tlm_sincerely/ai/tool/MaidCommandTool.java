package com.github.tartaricacid.tlm_sincerely.ai.tool;

import com.github.tartaricacid.tlm_sincerely.command.CommandAuditLog;
import com.github.tartaricacid.tlm_sincerely.command.CommandClassifier;
import com.github.tartaricacid.tlm_sincerely.command.CommandConfirmationService;
import com.github.tartaricacid.tlm_sincerely.command.CommandRateLimiter;
import com.github.tartaricacid.tlm_sincerely.command.MaidCommandExecutor;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.MaidCommandConfig;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * AI tool that lets the maid execute one Minecraft command as her owner.
 *
 * <p>The identity is always the owner player (plan D1): position, dimension,
 * rotation, {@code @s} and relative coordinates resolve against
 * {@code owner.createCommandSourceStack()}, and the permission level is the
 * owner's real level capped by {@code MaxPermissionLevel}. When the owner is
 * offline the command is refused instead of falling back to the maid's own
 * permission level (plan D3).
 */
public class MaidCommandTool implements ITool<MaidCommandTool.Result> {
    private static final String TOOL_ID = "run_command";

    private static final String TOOL_DESC = """
            Execute one Minecraft command as the maid's owner (the player who owns this maid).
            The command is run with the owner's identity, never the maid's: position, rotation, dimension, relative coordinates, '@s' and the permission level all belong to the owner, so '/give @s ...' gives the item to the owner and '~ ~ ~' is the owner's position.
            Use it when the player explicitly asks for a game action that has no dedicated tool (giving items, teleporting, killing mobs, weather, time, filling blocks, ...).
            The 'command' parameter must be exactly one command in plain text, without a leading slash and without line breaks.
            Some commands are permanently blocked and cannot be executed at all; some destructive commands require the owner to confirm in the chat bar first, so the result may be a cancellation or a timeout instead of output.
            The owner must be online: if the owner is offline the command cannot run.
            Do not use this tool for things that already have a dedicated tool (switching the maid's work task, follow state, or the maid's memory).
            """.trim();

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("command", "").forGetter(Result::command)
    ).apply(instance, Result::new));

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return TOOL_DESC;
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        StringParameter command = StringParameter.create()
                .setDescription("A single complete Minecraft command without a leading '/', for example "
                        + "'give @s diamond 1' or 'execute run kill @e[type=zombie,distance=..10]'. "
                        + "It must not contain line breaks; only one command per call.");
        root.addProperties("command", command);
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public boolean trigger(EntityMaid maid, ChatCompletion chatCompletion) {
        return MaidCommandConfig.COMMAND_TOOL_ENABLED.get();
    }

    /**
     * Safety net only. TLM 1.5.3 calls {@link #onCallAsync} and this tool must
     * never silently execute without the classification/confirmation flow, so
     * the synchronous path refuses instead.
     */
    @Override
    public LLMCallback onCall(String toolCallId, Result result, LLMCallback callback) {
        return callback.addToolResult(
                "This tool requires Touhou Little Maid 1.5.2 or newer; the command was not executed.",
                toolCallId);
    }

    @Override
    public CompletableFuture<LLMCallback> onCallAsync(String toolCallId, Result result,
                                                      LLMCallback callback, LLMClient client) {
        if (callback.isOnServerThread()) {
            return dispatch(toolCallId, result, callback);
        }
        // Defensive: TLM dispatches tools on the server thread. If a call ever
        // arrives off-thread it is re-scheduled before touching the world. A
        // side without a server level cannot add a tool result at all, so the
        // chain is released unchanged instead of stalling forever.
        EntityMaid maid = callback.getMaid();
        if (maid == null || !(maid.level() instanceof ServerLevel)) {
            return CompletableFuture.completedFuture(callback);
        }
        CompletableFuture<LLMCallback> scheduled = new CompletableFuture<>();
        callback.runOnServerThread(() -> {
            try {
                dispatch(toolCallId, result, callback).whenComplete((completed, throwable) -> {
                    if (throwable != null) {
                        scheduled.completeExceptionally(throwable);
                    } else {
                        scheduled.complete(completed);
                    }
                });
            } catch (Throwable throwable) {
                scheduled.completeExceptionally(throwable);
            }
        });
        return scheduled;
    }

    @Override
    public Component invocationSummaryComponent(Result result) {
        return Component.translatable("tool.tlm_sincerely.run_command.summary",
                summarize(result.command()));
    }

    private CompletableFuture<LLMCallback> dispatch(String toolCallId, Result result, LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        if (maid == null) {
            return completed(callback.addToolResult(
                    "No maid context is available for this tool call.", toolCallId));
        }
        if (!(maid.level() instanceof ServerLevel serverLevel)) {
            return completed(callback.addToolResult(
                    "Command execution is only available on the server side.", toolCallId));
        }
        MinecraftServer server = serverLevel.getServer();

        if (!MaidCommandConfig.COMMAND_TOOL_ENABLED.get()) {
            return completed(callback.addToolResult(
                    "The command execution tool is disabled; the command was not executed.", toolCallId));
        }

        int attempts = CommandRateLimiter.recordAttempt(callback);
        int maxAttempts = MaidCommandConfig.MAX_COMMANDS_PER_REQUEST.get();
        if (attempts > maxAttempts) {
            CommandAuditLog.log(maid, ownerOrNull(maid),
                    CommandAuditLog.Decision.RATE_LIMITED, 0, safeCommand(result.command()), null);
            return completed(callback.addToolResult(
                    "Command attempt limit reached for this conversation (%d); the command was not executed."
                            .formatted(maxAttempts), toolCallId));
        }

        NormalizedCommand normalized = NormalizedCommand.of(result.command(),
                MaidCommandConfig.MAX_COMMAND_LENGTH.get());
        if (normalized.error() != null) {
            CommandAuditLog.log(maid, ownerOrNull(maid),
                    CommandAuditLog.Decision.INVALID, 0, safeCommand(result.command()), null);
            return completed(callback.addToolResult(normalized.error(), toolCallId));
        }
        String command = normalized.command();

        LivingEntity ownerEntity = maid.getOwner();
        if (!(ownerEntity instanceof ServerPlayer owner) || !owner.isAlive()) {
            CommandAuditLog.log(maid, null,
                    CommandAuditLog.Decision.REJECTED_OWNER_OFFLINE, 0, command, null);
            return completed(callback.addToolResult(
                    "The maid's owner is offline or unavailable, and commands only run with the owner's "
                            + "identity; the command was not executed.", toolCallId));
        }

        CommandClassifier.Result classification = CommandClassifier.classify(server, command);
        if (classification.decision() == CommandClassifier.Decision.BLOCKED) {
            CommandAuditLog.log(maid, owner,
                    CommandAuditLog.Decision.BLOCKED_BLACKLIST, 0, command, classification.matchedName());
            return completed(callback.addToolResult(
                    "Command unavailable: this command is permanently blocked by the server policy "
                            + "and was not executed.", toolCallId));
        }

        if (classification.decision() == CommandClassifier.Decision.CONFIRM
                && MaidCommandConfig.CONFIRMATION_ENABLED.get()) {
            CommandConfirmationService service = CommandConfirmationService.getOrNull(server);
            if (service == null) {
                CommandAuditLog.log(maid, owner, CommandAuditLog.Decision.INVALID, 0, command,
                        classification.matchedName());
                return completed(callback.addToolResult(
                        "The command confirmation service is unavailable; the command was not executed.",
                        toolCallId));
            }
            if (service.isTrusted(owner.getUUID(), classification.matchedName())) {
                String text = MaidCommandExecutor.runAndReport(maid, owner, command,
                        classification.matchedName(), CommandAuditLog.Decision.SESSION_TRUSTED_EXECUTED);
                return completed(callback.addToolResult(text, toolCallId));
            }
            return service.awaitConfirmation(maid, owner, command, classification.matchedName(),
                    callback, toolCallId);
        }

        String text = MaidCommandExecutor.runAndReport(maid, owner, command,
                classification.matchedName(), CommandAuditLog.Decision.EXECUTED);
        return completed(callback.addToolResult(text, toolCallId));
    }

    private static ServerPlayer ownerOrNull(EntityMaid maid) {
        return maid.getOwner() instanceof ServerPlayer owner ? owner : null;
    }

    private static String safeCommand(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static String summarize(String raw) {
        String value = safeCommand(raw);
        if (value.length() > 40) {
            return value.substring(0, 40) + "...";
        }
        return value;
    }

    private static CompletableFuture<LLMCallback> completed(LLMCallback callback) {
        return CompletableFuture.completedFuture(callback);
    }

    public record Result(String command) {
    }

    /**
     * Normalization per plan 4.3.1: trim, drop one leading slash, refuse line
     * breaks, tabs and over-long input.
     */
    private record NormalizedCommand(String command, String error) {
        static NormalizedCommand of(String raw, int maxLength) {
            if (raw == null) {
                return invalid("<single command text>", "command is required");
            }
            String value = raw.trim();
            if (value.startsWith("/")) {
                value = value.substring(1).trim();
            }
            if (value.isEmpty()) {
                return invalid("<single command text>", "command is required");
            }
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\t') >= 0) {
                return invalid("<one command in a single line>",
                        "command must not contain line breaks or tabs");
            }
            if (value.length() > maxLength) {
                return invalid("<command within " + maxLength + " characters>",
                        "command is too long (max " + maxLength + " characters)");
            }
            return new NormalizedCommand(value, null);
        }

        private static NormalizedCommand invalid(String usage, String reason) {
            return new NormalizedCommand("", ITool.invalidParam("command", List.of(usage), reason));
        }
    }
}
