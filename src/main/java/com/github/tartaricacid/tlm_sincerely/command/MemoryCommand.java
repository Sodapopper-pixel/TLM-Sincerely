package com.github.tartaricacid.tlm_sincerely.command;

import com.github.tartaricacid.tlm_sincerely.chatbar.MaidFinder;
import com.github.tartaricacid.tlm_sincerely.chatbar.MaidFinder.FindResult;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.MemoryConfig;
import com.github.tartaricacid.tlm_sincerely.memory.MaidMemory;
import com.github.tartaricacid.tlm_sincerely.memory.MaidMemoryManager;
import com.github.tartaricacid.tlm_sincerely.memory.MaidMemory.MemoryEntry;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class MemoryCommand {
    private static final String DEFAULT_LANGUAGE = "en_us";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private static final String SUMMARIZE_PROMPT = """
            Please review our conversation so far and use tlm_memory remember \
            to record anything you missed: things the player asked you to remember, \
            lasting preferences, personal facts, significant events. \
            Use short semantic English keys and one concise sentence with context. \
            Do not record small talk or transient game state.""";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tlmmemory")
                .then(Commands.literal("set")
                        .then(Commands.argument("maid", UnicodeWordArgument.word("maid"))
                                .suggests(MemoryCommand::suggestMaidNames)
                                .then(Commands.argument("key", UnicodeWordArgument.word("key"))
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> setMemory(ctx, MemoryEntry.ARCHIVE))))))
                .then(Commands.literal("set-core")
                        .then(Commands.argument("maid", UnicodeWordArgument.word("maid"))
                                .suggests(MemoryCommand::suggestMaidNames)
                                .then(Commands.argument("key", UnicodeWordArgument.word("key"))
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> setMemory(ctx, MemoryEntry.CORE))))))
                .then(Commands.literal("get")
                        .then(Commands.argument("maid", UnicodeWordArgument.word("maid"))
                                .suggests(MemoryCommand::suggestMaidNames)
                                .then(Commands.argument("key", UnicodeWordArgument.word("key"))
                                        .executes(MemoryCommand::getMemory))))
                .then(Commands.literal("list")
                        .then(Commands.argument("maid", UnicodeWordArgument.word("maid"))
                                .suggests(MemoryCommand::suggestMaidNames)
                                .executes(MemoryCommand::listMemories)))
                .then(Commands.literal("forget")
                        .then(Commands.argument("maid", UnicodeWordArgument.word("maid"))
                                .suggests(MemoryCommand::suggestMaidNames)
                                .then(Commands.argument("key", UnicodeWordArgument.word("key"))
                                        .executes(MemoryCommand::forgetMemory))))
                .then(Commands.literal("export")
                        .then(Commands.argument("maid", UnicodeWordArgument.word("maid"))
                                .suggests(MemoryCommand::suggestMaidNames)
                                .executes(ctx -> exportMemory(ctx, "json"))
                                .then(Commands.literal("json")
                                        .executes(ctx -> exportMemory(ctx, "json")))
                                .then(Commands.literal("text")
                                        .executes(ctx -> exportMemory(ctx, "text")))
                                .then(Commands.literal("context")
                                        .executes(ctx -> exportMemory(ctx, "context")))))
                .then(Commands.literal("summarize")
                        .then(Commands.argument("maid", UnicodeWordArgument.word("maid"))
                                .suggests(MemoryCommand::suggestMaidNames)
                                .executes(MemoryCommand::summarizeMemories)))
        );
    }

    private static CompletableFuture<Suggestions> suggestMaidNames(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        List<EntityMaid> maids = MaidFinder.getOwnedMaids(player);

        String input = builder.getRemaining().toLowerCase();
        for (EntityMaid maid : maids) {
            String name = maid.getName().getString();
            if (name.toLowerCase().startsWith(input)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }

    private static EntityMaid resolveMaid(ServerPlayer player, String maidSelector) {
        if (maidSelector.startsWith("uuid:")) {
            return MaidFinder.findByUuid(player, maidSelector.substring(5));
        }
        FindResult result = MaidFinder.findByName(player, maidSelector);
        return result != null ? result.maid() : null;
    }

    private static int setMemory(CommandContext<CommandSourceStack> context, String importance)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String maidSelector = UnicodeWordArgument.get(context, "maid");
        String key = UnicodeWordArgument.get(context, "key");
        String value = StringArgumentType.getString(context, "value");

        if (!MemoryConfig.ENABLED.get()) {
            player.sendSystemMessage(Component.translatable("command.tlm_sincerely.memory.disabled")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        EntityMaid maid = resolveMaid(player, maidSelector);
        if (maid == null) {
            player.sendSystemMessage(Component.translatable("command.tlm_sincerely.memory.not_found")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        MaidMemory memory = MaidMemoryManager.load(maid.getUUID());
        int max = MemoryConfig.MAX_MEMORIES.get();
        if (memory.size() >= max && !memory.getMemories().containsKey(key)) {
            player.sendSystemMessage(Component.translatable("command.tlm_sincerely.memory.full", max)
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        memory.set(key, value, importance, player.getName().getString());
        if (!MaidMemoryManager.save(maid.getUUID(), memory)) {
            player.sendSystemMessage(Component.translatable("command.tlm_sincerely.memory.save_failed")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        String maidName = maid.getName().getString();
        player.sendSystemMessage(Component.translatable(
                "command.tlm_sincerely.memory.set_success", maidName, key)
                .withStyle(ChatFormatting.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private static int getMemory(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String maidSelector = UnicodeWordArgument.get(context, "maid");
        String key = UnicodeWordArgument.get(context, "key");

        EntityMaid maid = resolveMaid(player, maidSelector);
        if (maid == null) {
            player.sendSystemMessage(Component.translatable("command.tlm_sincerely.memory.not_found")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        MaidMemory memory = MaidMemoryManager.load(maid.getUUID());
        Optional<MemoryEntry> entry = memory.get(key);
        if (entry.isEmpty()) {
            player.sendSystemMessage(Component.translatable("command.tlm_sincerely.memory.key_not_found", key)
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        MemoryEntry mem = entry.get();
        String maidName = maid.getName().getString();
        player.sendSystemMessage(Component.literal("=== " + maidName + ": " + key + " ===")
                .withStyle(ChatFormatting.GOLD));

        MutableComponent valueLine = Component.literal("  ").append(
                Component.literal(mem.value()).withStyle(ChatFormatting.WHITE));
        player.sendSystemMessage(valueLine);

        MutableComponent metaLine = Component.literal("  ")
                .append(Component.literal("importance: " + mem.importance()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("  |  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("created: " + DATE_FORMAT.format(new Date(mem.createdAt())))
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal("  |  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("updated: " + DATE_FORMAT.format(new Date(mem.updatedAt())))
                        .withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(metaLine);

        MutableComponent statsLine = Component.literal("  ")
                .append(Component.literal("accessed: " + mem.accessCount() + " times").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("  |  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("last access: " + (mem.lastAccessedAt() > 0
                        ? DATE_FORMAT.format(new Date(mem.lastAccessedAt())) : "never")).withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(statsLine);

        if (MemoryConfig.SHOW_SOURCE.get() && !mem.source().isEmpty()) {
            MutableComponent sourceLine = Component.literal("  ")
                    .append(Component.literal("source: " + mem.source()).withStyle(ChatFormatting.GRAY));
            player.sendSystemMessage(sourceLine);
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int listMemories(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String maidSelector = UnicodeWordArgument.get(context, "maid");

        EntityMaid maid = resolveMaid(player, maidSelector);
        if (maid == null) {
            player.sendSystemMessage(Component.translatable("command.tlm_sincerely.memory.not_found")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        MaidMemory memory = MaidMemoryManager.load(maid.getUUID());
        String maidName = maid.getName().getString();

        if (memory.isEmpty()) {
            player.sendSystemMessage(Component.literal(maidName + " has no memories")
                    .withStyle(ChatFormatting.YELLOW));
            return Command.SINGLE_SUCCESS;
        }

        player.sendSystemMessage(Component.literal("=== " + maidName + " (" + memory.size() + " memories) ===")
                .withStyle(ChatFormatting.GOLD));

        boolean showSource = MemoryConfig.SHOW_SOURCE.get();
        for (Map.Entry<String, MemoryEntry> entry : memory.getMemories().entrySet()) {
            MemoryEntry mem = entry.getValue();
            String tag = MemoryEntry.CORE.equals(mem.importance()) ? "★" : "·";
            String preview = mem.value().length() > 30 ? mem.value().substring(0, 30) + "..." : mem.value();

            MutableComponent line = Component.literal("  " + tag + " ")
                    .withStyle(MemoryEntry.CORE.equals(mem.importance())
                            ? ChatFormatting.GOLD : ChatFormatting.GRAY)
                    .append(Component.literal(entry.getKey()).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(preview).withStyle(ChatFormatting.WHITE));

            if (showSource && !mem.source().isEmpty()) {
                line.append(Component.literal(" (" + mem.source() + ")").withStyle(ChatFormatting.DARK_GRAY));
            }

            player.sendSystemMessage(line);
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int forgetMemory(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String maidSelector = UnicodeWordArgument.get(context, "maid");
        String key = UnicodeWordArgument.get(context, "key");

        EntityMaid maid = resolveMaid(player, maidSelector);
        if (maid == null) {
            player.sendSystemMessage(Component.translatable("command.tlm_sincerely.memory.not_found")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        MaidMemory memory = MaidMemoryManager.load(maid.getUUID());
        if (!memory.getMemories().containsKey(key)) {
            player.sendSystemMessage(Component.translatable("command.tlm_sincerely.memory.key_not_found", key)
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        memory.forget(key);
        if (!MaidMemoryManager.save(maid.getUUID(), memory)) {
            player.sendSystemMessage(Component.translatable("command.tlm_sincerely.memory.save_failed")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        String maidName = maid.getName().getString();
        player.sendSystemMessage(Component.translatable(
                "command.tlm_sincerely.memory.forget_success", maidName, key)
                .withStyle(ChatFormatting.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private static int exportMemory(CommandContext<CommandSourceStack> context, String format)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String maidSelector = UnicodeWordArgument.get(context, "maid");

        EntityMaid maid = resolveMaid(player, maidSelector);
        if (maid == null) {
            player.sendSystemMessage(Component.translatable("command.tlm_sincerely.memory.not_found")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        MaidMemory memory = MaidMemoryManager.load(maid.getUUID());
        String maidName = maid.getName().getString();

        player.sendSystemMessage(Component.literal("=== " + maidName + " Memory Export (" + format + ") ===")
                .withStyle(ChatFormatting.GOLD));

        String output = switch (format) {
            case "json" -> exportJson(memory);
            case "text" -> exportText(maidName, memory);
            case "context" -> exportContext(memory);
            default -> exportJson(memory);
        };

        for (String line : output.split("\n")) {
            player.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.GRAY));
        }

        return Command.SINGLE_SUCCESS;
    }

    private static String exportJson(MaidMemory memory) {
        // 用 Gson 序列化而非手工拼接，保证 key（可能由 LLM 生成）和 value 中的
        // 引号、反斜杠等字符被正确转义，输出始终是合法 JSON
        Map<String, String> export = new LinkedHashMap<>();
        for (Map.Entry<String, MemoryEntry> entry : memory.getMemories().entrySet()) {
            export.put(entry.getKey(), entry.getValue().value());
        }
        return new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(export);
    }

    private static String exportText(String maidName, MaidMemory memory) {
        StringBuilder sb = new StringBuilder();
        sb.append(maidName).append("'s Memories\n");
        sb.append("=".repeat(40)).append("\n");
        for (Map.Entry<String, MemoryEntry> entry : memory.getMemories().entrySet()) {
            MemoryEntry mem = entry.getValue();
            String tag = MemoryEntry.CORE.equals(mem.importance()) ? "[core]" : "[archive]";
            sb.append("\n**").append(entry.getKey()).append("** ").append(tag).append("\n");
            sb.append("  ").append(mem.value()).append("\n");
            sb.append("  _created: ").append(DATE_FORMAT.format(new Date(mem.createdAt())))
                    .append(", updated: ").append(DATE_FORMAT.format(new Date(mem.updatedAt()))).append("_\n");
        }
        return sb.toString();
    }

    private static String exportContext(MaidMemory memory) {
        return memory.generateContextPreview(
                MemoryConfig.CORE_LIMIT.get(),
                MemoryConfig.CONTEXT_PREVIEW_LENGTH.get(),
                MemoryConfig.PREVIEW_MODE.get(),
                MemoryConfig.SHOW_SOURCE.get()
        );
    }

    private static int summarizeMemories(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String maidSelector = UnicodeWordArgument.get(context, "maid");

        if (!MemoryConfig.ENABLED.get()) {
            player.sendSystemMessage(Component.translatable("command.tlm_sincerely.memory.disabled")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        EntityMaid maid = resolveMaid(player, maidSelector);
        if (maid == null) {
            player.sendSystemMessage(Component.translatable("command.tlm_sincerely.memory.not_found")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        String maidName = maid.getName().getString();
        String language = maid.getAiChatManager().getTTSLanguage();
        if (language == null || language.isEmpty()) {
            language = DEFAULT_LANGUAGE;
        }
        ChatClientInfo clientInfo = new ChatClientInfo(language, maidName, List.of());
        maid.getAiChatManager().chat(SUMMARIZE_PROMPT, clientInfo, player);

        player.sendSystemMessage(Component.translatable(
                "command.tlm_sincerely.memory.summarize_sent", maidName)
                .withStyle(ChatFormatting.GREEN));
        return Command.SINGLE_SUCCESS;
    }
}
