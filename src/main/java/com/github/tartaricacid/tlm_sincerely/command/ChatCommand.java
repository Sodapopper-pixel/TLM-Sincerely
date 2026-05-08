package com.github.tartaricacid.tlm_sincerely.command;

import com.github.tartaricacid.tlm_sincerely.chatbar.MaidFinder;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public final class ChatCommand {
    private static final String DEFAULT_LANGUAGE = "en_us";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tlmchat")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ChatCommand::chatWithNearest))
                .then(Commands.literal("to")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ChatCommand::chatWithNameSplit)))
        );
    }

    private static int chatWithNearest(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String message = StringArgumentType.getString(context, "message");

        EntityMaid maid = MaidFinder.findNearest(player);
        if (maid == null) {
            player.sendSystemMessage(Component.translatable("chat.tlm_sincerely.no_maid_nearby")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        sendChatMessage(maid, player, message);
        return Command.SINGLE_SUCCESS;
    }

    private static int chatWithNameSplit(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String fullArg = StringArgumentType.getString(context, "name");
        
        String[] parts = fullArg.split("\\s+", 2);
        if (parts.length < 2) {
            player.sendSystemMessage(Component.translatable("chat.tlm_sincerely.invalid_format")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        
        String name = parts[0];
        String message = parts[1];

        EntityMaid maid = MaidFinder.findByName(player, name);
        if (maid == null) {
            player.sendSystemMessage(Component.translatable("chat.tlm_sincerely.maid_not_found")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        sendChatMessage(maid, player, message);
        return Command.SINGLE_SUCCESS;
    }

    private static void sendChatMessage(EntityMaid maid, ServerPlayer player, String message) {
        ChatClientInfo clientInfo = createServerSideClientInfo(maid);
        maid.getAiChatManager().chat(message, clientInfo, player);
    }

    private static ChatClientInfo createServerSideClientInfo(EntityMaid maid) {
        String language = maid.getAiChatManager().getTTSLanguage();
        if (language == null || language.isEmpty()) {
            language = DEFAULT_LANGUAGE;
        }
        String name = maid.getName().getString();
        List<String> description = List.of();
        return new ChatClientInfo(language, name, description);
    }
}