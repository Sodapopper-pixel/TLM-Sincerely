package com.github.tartaricacid.tlm_sincerely.priority.autowork.compat;

import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.function.Consumer;

/** Formats the paginated auto-work compatibility report for chat and commands. */
public final class AutoWorkCompatReport {
    public static final int PAGE_SIZE = 6;

    private AutoWorkCompatReport() {
    }

    public static void send(Consumer<Component> sender, AutoWorkCompatService service,
                            AutoWorkCompatService.ReportFilter filter, int requestedPage) {
        List<AutoWorkCompatService.ReportEntry> entries = switch (filter) {
            case PROBLEMS -> service.problemEntries();
            case ALL -> service.getEntries();
            case SUPPORTED, FALLBACK, UNSUPPORTED, BLOCKED -> service.entriesByLevel(filter.level());
        };
        int totalPages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(Math.max(requestedPage, 1), totalPages);
        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, entries.size());

        sender.accept(Component.translatable("chat.tlm_sincerely.autowork.compat.title")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        sender.accept(Component.translatable("chat.tlm_sincerely.autowork.compat.divider")
                .withStyle(ChatFormatting.DARK_GRAY));
        sender.accept(summaryLine(service));
        sender.accept(Component.translatable("chat.tlm_sincerely.autowork.compat.divider")
                .withStyle(ChatFormatting.DARK_GRAY));

        if (entries.isEmpty()) {
            sender.accept(Component.translatable(filter == AutoWorkCompatService.ReportFilter.PROBLEMS
                            ? "chat.tlm_sincerely.autowork.compat.empty"
                            : "chat.tlm_sincerely.autowork.compat.empty.filter")
                    .withStyle(ChatFormatting.GREEN));
        } else {
            for (int index = start; index < end; index++) {
                AutoWorkCompatService.ReportEntry entry = entries.get(index);
                sender.accept(itemLine(index + 1, entry));
                sender.accept(Component.translatable("chat.tlm_sincerely.autowork.compat.reason",
                                reasonText(entry.reason()))
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }

        sender.accept(Component.translatable("chat.tlm_sincerely.autowork.compat.divider")
                .withStyle(ChatFormatting.DARK_GRAY));
        sender.accept(navigation(filter, page, totalPages));
    }

    private static MutableComponent summaryLine(AutoWorkCompatService service) {
        MutableComponent line = Component.empty();
        line.append(countButton("chat.tlm_sincerely.autowork.compat.summary.supported",
                service.count(AutoWorkCompatService.Level.SUPPORTED), AutoWorkCompatService.Level.SUPPORTED));
        line.append(Component.literal("  |  ").withStyle(ChatFormatting.DARK_GRAY));
        line.append(countButton("chat.tlm_sincerely.autowork.compat.summary.fallback",
                service.count(AutoWorkCompatService.Level.FALLBACK), AutoWorkCompatService.Level.FALLBACK));
        line.append(Component.literal("  |  ").withStyle(ChatFormatting.DARK_GRAY));
        line.append(countButton("chat.tlm_sincerely.autowork.compat.summary.unsupported",
                service.count(AutoWorkCompatService.Level.UNSUPPORTED), AutoWorkCompatService.Level.UNSUPPORTED));
        line.append(Component.literal("  |  ").withStyle(ChatFormatting.DARK_GRAY));
        line.append(countButton("chat.tlm_sincerely.autowork.compat.summary.blocked",
                service.count(AutoWorkCompatService.Level.BLOCKED), AutoWorkCompatService.Level.BLOCKED));
        return line;
    }

    private static MutableComponent countButton(String labelKey, int count, AutoWorkCompatService.Level level) {
        return Component.translatable(labelKey, count)
                .withStyle(style -> style.withColor(levelColor(level))
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/tlmautowork compat "
                                        + AutoWorkCompatService.ReportFilter.forLevel(level).commandPrefix() + " 1"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable(labelKey + ".hover"))));
    }

    private static MutableComponent itemLine(int number, AutoWorkCompatService.ReportEntry entry) {
        String taskName = TaskManager.findTask(entry.uid())
                .map(task -> task.getName().getString())
                .orElse(entry.uid().toString());
        return Component.translatable("chat.tlm_sincerely.autowork.compat.item",
                        number, levelText(entry.level()), taskName, entry.uid().toString())
                .withStyle(levelColor(entry.level()));
    }

    private static MutableComponent navigation(AutoWorkCompatService.ReportFilter filter, int page, int totalPages) {
        MutableComponent line = Component.empty();
        if (page > 1) {
            line.append(navButton("chat.tlm_sincerely.autowork.compat.prev", filter, page - 1,
                    "chat.tlm_sincerely.autowork.compat.prev.hover"));
            line.append(Component.literal("  "));
        }
        line.append(Component.translatable("chat.tlm_sincerely.autowork.compat.page", page, totalPages)
                .withStyle(ChatFormatting.GRAY));
        if (page < totalPages) {
            line.append(Component.literal("  "));
            line.append(navButton("chat.tlm_sincerely.autowork.compat.next", filter, page + 1,
                    "chat.tlm_sincerely.autowork.compat.next.hover"));
        }
        return line;
    }

    private static MutableComponent navButton(String labelKey, AutoWorkCompatService.ReportFilter filter,
                                              int page, String hoverKey) {
        return Component.translatable(labelKey)
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/tlmautowork compat " + filter.commandPrefix() + " " + page))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable(hoverKey))));
    }

    private static Component levelText(AutoWorkCompatService.Level level) {
        return Component.translatable("chat.tlm_sincerely.autowork.compat.level." + level.name().toLowerCase());
    }

    private static Component reasonText(String reason) {
        return Component.translatable("chat.tlm_sincerely.autowork.compat.reason." + reason);
    }

    private static ChatFormatting levelColor(AutoWorkCompatService.Level level) {
        return switch (level) {
            case BLOCKED -> ChatFormatting.RED;
            case UNSUPPORTED -> ChatFormatting.GOLD;
            case FALLBACK -> ChatFormatting.YELLOW;
            case SUPPORTED -> ChatFormatting.GREEN;
        };
    }
}
