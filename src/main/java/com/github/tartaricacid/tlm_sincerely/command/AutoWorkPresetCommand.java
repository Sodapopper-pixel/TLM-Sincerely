package com.github.tartaricacid.tlm_sincerely.command;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.push.AutoWorkPushService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Chat-button endpoints for preset pushes: accepting or rejecting an offer
 * created by {@link AutoWorkPushService}. Registered as a child of the
 * {@code /tlmautowork} root, which Brigadier merges with the compat branch.
 */
public final class AutoWorkPresetCommand {
    private AutoWorkPresetCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tlmautowork")
                .then(Commands.literal("preset")
                        .then(Commands.literal("accept")
                                .then(Commands.argument("token", StringArgumentType.word())
                                        .executes(context -> handle(context, true))))
                        .then(Commands.literal("reject")
                                .then(Commands.argument("token", StringArgumentType.word())
                                        .executes(context -> handle(context, false))))));
    }

    private static int handle(CommandContext<CommandSourceStack> context, boolean accept) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("This command can only be used by a player."));
            return 0;
        }
        AutoWorkPushService service = AutoWorkPushService.getOrNull(player.server);
        if (service == null) {
            context.getSource().sendFailure(Component.literal("Preset push service is not ready."));
            return 0;
        }
        UUID token;
        try {
            token = UUID.fromString(StringArgumentType.getString(context, "token"));
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Component.translatable(
                    "command.tlm_sincerely.autowork.push.unknown_offer"));
            return 0;
        }
        boolean handled = accept ? service.accept(player, token) : service.reject(player, token);
        if (!handled) {
            context.getSource().sendFailure(Component.translatable(
                    "command.tlm_sincerely.autowork.push.unknown_offer"));
            return 0;
        }
        return 1;
    }
}
