package com.github.tartaricacid.tlm_sincerely.command;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.MaidCommandConfig;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Pending command confirmations and per-session trust.
 *
 * <p>All state is per-server and lives on the server thread: the tool call,
 * the {@code /tlmconfirm} command, the tick scan, logout and server stop are
 * the only entry points. Every termination path completes the stored future
 * on the server thread, which is what TLM's {@code addToolResult} requires.
 */
public final class CommandConfirmationService {
    private static final Logger LOGGER = LoggerFactory.getLogger("TLM_Sincerely/MaidCommand");
    private static final Map<MinecraftServer, CommandConfirmationService> INSTANCES = new IdentityHashMap<>();
    private static final int TICK_SCAN_INTERVAL = 20;

    public enum Action {
        RUN,
        CANCEL,
        TRUST
    }

    private final MinecraftServer server;
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    private final Map<UUID, Set<String>> sessionTrust = new HashMap<>();
    private int tickCounter;

    private CommandConfirmationService(MinecraftServer server) {
        this.server = server;
    }

    public static CommandConfirmationService bind(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, CommandConfirmationService::new);
    }

    public static void unbind(MinecraftServer server) {
        CommandConfirmationService service = INSTANCES.remove(server);
        if (service != null) {
            service.cancelAll("The server stopped before the command was confirmed; the command was not executed.");
        }
    }

    public static CommandConfirmationService getOrNull(MinecraftServer server) {
        return server == null ? null : INSTANCES.get(server);
    }

    public boolean hasPendingForMaid(UUID maidUuid) {
        for (Pending entry : pending.values()) {
            if (entry.maid.getUUID().equals(maidUuid)) {
                return true;
            }
        }
        return false;
    }

    public boolean isTrusted(UUID ownerUuid, String hitName) {
        if (!MaidCommandConfig.SESSION_CONFIRMATION_ENABLED.get()) {
            return false;
        }
        Set<String> trusted = sessionTrust.get(ownerUuid);
        return trusted != null && trusted.contains(hitName);
    }

    /**
     * Suspends the tool chain until the owner confirms, cancels or the request
     * times out. Must be called on the server thread.
     */
    public CompletableFuture<LLMCallback> awaitConfirmation(EntityMaid maid, ServerPlayer owner, String command,
                                                            String hitName, LLMCallback callback, String toolCallId) {
        if (hasPendingForMaid(maid.getUUID())) {
            String text = "A command confirmation is already pending for this maid; "
                    + "the owner must confirm or cancel it before another command can run.";
            CommandAuditLog.log(maid, owner, CommandAuditLog.Decision.INVALID, 0, command, hitName);
            callback.addToolResult(text, toolCallId);
            return CompletableFuture.completedFuture(callback);
        }

        UUID token = UUID.randomUUID();
        int timeoutSeconds = MaidCommandConfig.CONFIRMATION_TIMEOUT_SECONDS.get();
        Pending entry = new Pending(token, maid, owner.getUUID(), command, hitName,
                server.getTickCount() + timeoutSeconds * 20, timeoutSeconds, callback, toolCallId);
        pending.put(token, entry);

        try {
            owner.sendSystemMessage(buildConfirmationMessage(entry));
            callback.refreshWaitingChatBubble(Component.translatable(
                    "command.tlm_sincerely.run_command.waiting_bubble",
                    MaidCommandExecutor.sanitizeForDisplay(command)));
        } catch (Throwable throwable) {
            pending.remove(token);
            LOGGER.warn("Failed to send the command confirmation message: {}", throwable.getMessage());
            callback.addToolResult("Failed to show the command confirmation to the owner; "
                    + "the command was not executed.", toolCallId);
            return CompletableFuture.completedFuture(callback);
        }
        return entry.future;
    }

    /**
     * Handles a {@code /tlmconfirm run|cancel|trust <token>} click.
     *
     * @return {@code false} when the token is unknown, expired or belongs to
     * another player; the caller sends the error message.
     */
    public boolean handleAction(ServerPlayer player, UUID token, Action action) {
        Pending entry = pending.get(token);
        if (entry == null || !entry.ownerUuid.equals(player.getUUID())) {
            return false;
        }
        pending.remove(token);

        if (server.getTickCount() >= entry.deadlineTick) {
            cancel(entry, player, "The command confirmation timed out after %d seconds; the command was not executed."
                    .formatted(entry.timeoutSeconds), CommandAuditLog.Decision.TIMEOUT);
            return true;
        }

        switch (action) {
            case CANCEL -> {
                cancel(entry, player, "The owner cancelled the command confirmation; the command was not executed.",
                        CommandAuditLog.Decision.CANCELLED);
                player.sendSystemMessage(Component.translatable(
                        "command.tlm_sincerely.run_command.confirm_cancelled",
                        MaidCommandExecutor.sanitizeForDisplay(entry.command)
                ).withStyle(ChatFormatting.GRAY));
            }
            case RUN -> complete(entry, MaidCommandExecutor.runAndReport(entry.maid, player, entry.command,
                    entry.hitName, CommandAuditLog.Decision.CONFIRMED_EXECUTED));
            case TRUST -> {
                boolean sessionEnabled = MaidCommandConfig.SESSION_CONFIRMATION_ENABLED.get();
                if (sessionEnabled) {
                    sessionTrust.computeIfAbsent(entry.ownerUuid, key -> new HashSet<>()).add(entry.hitName);
                }
                complete(entry, MaidCommandExecutor.runAndReport(entry.maid, player, entry.command,
                        entry.hitName, sessionEnabled
                                ? CommandAuditLog.Decision.SESSION_TRUSTED_EXECUTED
                                : CommandAuditLog.Decision.CONFIRMED_EXECUTED));
            }
        }
        return true;
    }

    public void onOwnerLoggedOut(ServerPlayer player) {
        sessionTrust.remove(player.getUUID());
        UUID ownerUuid = player.getUUID();
        // Snapshot first: cancelling completes the model chain inline, and the
        // continuation may start another tool call that inserts a new pending.
        List<Pending> owned = new ArrayList<>();
        for (Pending entry : pending.values()) {
            if (entry.ownerUuid.equals(ownerUuid)) {
                owned.add(entry);
            }
        }
        for (Pending entry : owned) {
            pending.remove(entry.token);
            cancel(entry, player, "The owner went offline; the command was not executed.",
                    CommandAuditLog.Decision.CANCELLED);
        }
    }

    public void onServerTick() {
        if (++tickCounter % TICK_SCAN_INTERVAL != 0 || pending.isEmpty()) {
            return;
        }
        int now = server.getTickCount();
        List<Pending> snapshot = new ArrayList<>(pending.values());
        for (Pending entry : snapshot) {
            if (!pending.containsKey(entry.token)) {
                continue;
            }
            if (!entry.maid.isAlive() || entry.maid.isRemoved()) {
                pending.remove(entry.token);
                cancel(entry, ownerOf(entry), "The maid is no longer available; the command was not executed.",
                        CommandAuditLog.Decision.CANCELLED);
                continue;
            }
            if (now >= entry.deadlineTick) {
                pending.remove(entry.token);
                cancel(entry, ownerOf(entry),
                        "The command confirmation timed out after %d seconds; the command was not executed."
                                .formatted(entry.timeoutSeconds), CommandAuditLog.Decision.TIMEOUT);
            }
        }
    }

    private void cancelAll(String modelText) {
        List<Pending> snapshot = new ArrayList<>(pending.values());
        pending.clear();
        for (Pending entry : snapshot) {
            cancel(entry, ownerOf(entry), modelText, CommandAuditLog.Decision.CANCELLED);
        }
        sessionTrust.clear();
    }

    private ServerPlayer ownerOf(Pending entry) {
        return server.getPlayerList().getPlayer(entry.ownerUuid);
    }

    private void cancel(Pending entry, @Nullable ServerPlayer owner, String modelText,
                        CommandAuditLog.Decision decision) {
        CommandAuditLog.log(entry.maid, owner, decision, 0, entry.command, entry.hitName);
        complete(entry, modelText);
    }

    /** Adds the tool result and releases the waiting model chain. Server thread only. */
    private void complete(Pending entry, String modelText) {
        if (entry.future.isDone()) {
            return;
        }
        try {
            entry.callback.addToolResult(modelText, entry.toolCallId);
            entry.future.complete(entry.callback);
        } catch (Throwable throwable) {
            // TLM turns a failed future into a tool error result and keeps the
            // chain alive, so a broken addToolResult never hangs the dialogue.
            LOGGER.warn("Failed to complete the command confirmation: {}", throwable.getMessage());
            entry.future.completeExceptionally(throwable);
        }
    }

    private Component buildConfirmationMessage(Pending entry) {
        MutableComponent message = Component.translatable(
                "command.tlm_sincerely.run_command.confirm.message",
                entry.maid.getName().getString(),
                MaidCommandExecutor.sanitizeForDisplay(entry.command),
                String.valueOf(entry.timeoutSeconds)
        ).withStyle(ChatFormatting.YELLOW);

        message.append(Component.literal(" "));
        message.append(button("confirm", entry.token, "run", ChatFormatting.GREEN));
        message.append(Component.literal(" "));
        message.append(button("cancel", entry.token, "cancel", ChatFormatting.RED));
        if (MaidCommandConfig.SESSION_CONFIRMATION_ENABLED.get()) {
            message.append(Component.literal(" "));
            message.append(button("trust", entry.token, "trust", ChatFormatting.GOLD));
        }
        return message;
    }

    private MutableComponent button(String key, UUID token, String subcommand, ChatFormatting color) {
        return Component.translatable("command.tlm_sincerely.run_command.button." + key)
                .withStyle(Style.EMPTY
                        .withColor(color)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/tlmconfirm " + subcommand + " " + token)));
    }

    private static final class Pending {
        final UUID token;
        final EntityMaid maid;
        final UUID ownerUuid;
        final String command;
        final String hitName;
        final int deadlineTick;
        final int timeoutSeconds;
        final LLMCallback callback;
        final String toolCallId;
        final CompletableFuture<LLMCallback> future = new CompletableFuture<>();

        Pending(UUID token, EntityMaid maid, UUID ownerUuid, String command, String hitName,
                int deadlineTick, int timeoutSeconds, LLMCallback callback, String toolCallId) {
            this.token = token;
            this.maid = maid;
            this.ownerUuid = ownerUuid;
            this.command = command;
            this.hitName = hitName;
            this.deadlineTick = deadlineTick;
            this.timeoutSeconds = timeoutSeconds;
            this.callback = callback;
            this.toolCallId = toolCallId;
        }
    }
}
