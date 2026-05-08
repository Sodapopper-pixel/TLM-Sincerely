package com.github.tartaricacid.tlm_sincerely.command;

import com.github.tartaricacid.tlm_sincerely.chatbar.MaidFinder;
import com.github.tartaricacid.tlm_sincerely.chatbar.MaidFinder.FindResult;
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
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public final class ChatCommand {
    private static final String DEFAULT_LANGUAGE = "en_us";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tlmchat")
                .then(Commands.literal("to")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(ChatCommand::chatWithName))))
                .then(Commands.literal("uuid")
                        .then(Commands.argument("uuid", StringArgumentType.string())
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(ChatCommand::chatWithUuid))))
                .then(Commands.literal("list")
                        .executes(ChatCommand::listMaids))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ChatCommand::chatWithNearest))
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

    private static int chatWithName(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(context, "name");
        String message = StringArgumentType.getString(context, "message");

        FindResult result = MaidFinder.findByName(player, name);
        if (!result.hasMaid()) {
            player.sendSystemMessage(Component.translatable("chat.tlm_sincerely.maid_not_found")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        if (result.hasMultipleMatches()) {
            String uuidShort = result.maid().getUUID().toString().substring(0, 8);
            player.sendSystemMessage(Component.translatable(
                    "chat.tlm_sincerely.multiple_same_name", name, uuidShort
            ).withStyle(ChatFormatting.YELLOW));
        }

        sendChatMessage(result.maid(), player, message);
        return Command.SINGLE_SUCCESS;
    }

    private static int chatWithUuid(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String uuidString = StringArgumentType.getString(context, "uuid");
        String message = StringArgumentType.getString(context, "message");

        EntityMaid maid = MaidFinder.findByUuid(player, uuidString);
        if (maid == null) {
            player.sendSystemMessage(Component.translatable("chat.tlm_sincerely.maid_not_found_uuid")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        sendChatMessage(maid, player, message);
        return Command.SINGLE_SUCCESS;
    }

    private static int listMaids(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        List<EntityMaid> maids = MaidFinder.getOwnedMaids(player);

        if (maids.isEmpty()) {
            player.sendSystemMessage(Component.translatable("chat.tlm_sincerely.no_maid_nearby")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        player.sendSystemMessage(Component.translatable("chat.tlm_sincerely.maid_list_header")
                .withStyle(ChatFormatting.GREEN));

        for (EntityMaid maid : maids) {
            String name = maid.getName().getString();
            String uuid = maid.getUUID().toString().substring(0, 8);
            int distance = (int) maid.distanceTo(player);

            MutableComponent component = Component.literal("  - " + name)
                    .append(Component.literal(" [" + uuid + "]").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(" (" + distance + "m)").withStyle(ChatFormatting.AQUA));

            player.sendSystemMessage(component);
        }

        player.sendSystemMessage(Component.translatable("chat.tlm_sincerely.list_usage_hint")
                .withStyle(ChatFormatting.GRAY));

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