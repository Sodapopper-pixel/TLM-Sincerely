package com.github.tartaricacid.tlm_sincerely.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * {@code /tlmconfirm run|cancel|trust <token>} — the click target of the
 * owner-only confirmation message.
 *
 * <p>The command itself has no permission requirement so any owner can click
 * it; the service validates that the token exists, is not expired and belongs
 * to the clicking player (plan R8).
 */
public final class ConfirmCommand {
    private ConfirmCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tlmconfirm")
                .then(Commands.literal("run").then(tokenArgument(CommandConfirmationService.Action.RUN)))
                .then(Commands.literal("cancel").then(tokenArgument(CommandConfirmationService.Action.CANCEL)))
                .then(Commands.literal("trust").then(tokenArgument(CommandConfirmationService.Action.TRUST))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> tokenArgument(
            CommandConfirmationService.Action action) {
        return Commands.argument("token", StringArgumentType.word())
                .executes(context -> handle(context, action));
    }

    private static int handle(CommandContext<CommandSourceStack> context,
                              CommandConfirmationService.Action action) {
        ServerPlayer player = context.getSource().getPlayer();
        CommandConfirmationService service =
                CommandConfirmationService.getOrNull(context.getSource().getServer());
        if (player == null || service == null) {
            context.getSource().sendFailure(invalid());
            return 0;
        }
        UUID token = parseToken(StringArgumentType.getString(context, "token"));
        if (token == null || !service.handleAction(player, token, action)) {
            player.sendSystemMessage(invalid().withStyle(ChatFormatting.RED));
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private static UUID parseToken(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static MutableComponent invalid() {
        return Component.translatable("command.tlm_sincerely.run_command.confirm_invalid");
    }
}
