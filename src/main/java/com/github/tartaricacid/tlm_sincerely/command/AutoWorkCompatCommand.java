package com.github.tartaricacid.tlm_sincerely.command;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.compat.AutoWorkCompatReport;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.compat.AutoWorkCompatService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/** Server administration commands for the auto work compatibility policy. */
public final class AutoWorkCompatCommand {
    private AutoWorkCompatCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tlmautowork")
                .then(Commands.literal("compat")
                        .then(Commands.literal("report")
                                .executes(context -> report(context, AutoWorkCompatService.ReportFilter.PROBLEMS, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(context -> report(context, AutoWorkCompatService.ReportFilter.PROBLEMS,
                                                IntegerArgumentType.getInteger(context, "page"))))
                                .then(Commands.literal("all")
                                        .executes(context -> report(context, AutoWorkCompatService.ReportFilter.ALL, 1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(context -> report(context, AutoWorkCompatService.ReportFilter.ALL,
                                                        IntegerArgumentType.getInteger(context, "page"))))))
                        .then(reportBranch("supported", AutoWorkCompatService.ReportFilter.SUPPORTED))
                        .then(reportBranch("fallback", AutoWorkCompatService.ReportFilter.FALLBACK))
                        .then(reportBranch("unsupported", AutoWorkCompatService.ReportFilter.UNSUPPORTED))
                        .then(reportBranch("blocked", AutoWorkCompatService.ReportFilter.BLOCKED))
                        .then(Commands.literal("blacklist")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("uid", UnicodeWordArgument.word("uid"))
                                                .executes(context -> editList(context, true, true))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("uid", UnicodeWordArgument.word("uid"))
                                                .executes(context -> editList(context, true, false))))
                                .then(Commands.literal("list").executes(context -> list(context, true))))
                        .then(Commands.literal("whitelist")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("uid", UnicodeWordArgument.word("uid"))
                                                .executes(context -> editList(context, false, true))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("uid", UnicodeWordArgument.word("uid"))
                                                .executes(context -> editList(context, false, false))))
                                .then(Commands.literal("list").executes(context -> list(context, false))))
                        .then(Commands.literal("set")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.literal("reminder")
                                        .then(Commands.literal("ALL").executes(context -> setReminder(context,
                                                AutoWorkCompatService.ReminderLevel.ALL)))
                                        .then(Commands.literal("OP_ONLY").executes(context -> setReminder(context,
                                                AutoWorkCompatService.ReminderLevel.OP_ONLY)))
                                        .then(Commands.literal("DISABLED").executes(context -> setReminder(context,
                                                AutoWorkCompatService.ReminderLevel.DISABLED)))))
                        .then(Commands.literal("reload")
                                .requires(source -> source.hasPermission(2))
                                .executes(AutoWorkCompatCommand::reload))));
    }

    /** Literal branch with an optional page argument, e.g. {@code supported [page]}. */
    private static ArgumentBuilder<CommandSourceStack, ?> reportBranch(String literal,
                                                                       AutoWorkCompatService.ReportFilter filter) {
        return Commands.literal(literal)
                .executes(context -> report(context, filter, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> report(context, filter,
                                IntegerArgumentType.getInteger(context, "page"))));
    }

    private static int report(CommandContext<CommandSourceStack> context,
                              AutoWorkCompatService.ReportFilter filter, int page) {
        AutoWorkCompatService service = getService(context);
        if (service == null) {
            return 0;
        }
        AutoWorkCompatReport.send(message -> context.getSource().sendSuccess(() -> message, false), service, filter, page);
        return 1;
    }

    private static int editList(CommandContext<CommandSourceStack> context, boolean blacklist, boolean add) {
        AutoWorkCompatService service = getService(context);
        if (service == null) {
            return 0;
        }
        ResourceLocation uid = parseUid(context);
        if (uid == null) {
            context.getSource().sendFailure(Component.literal("Invalid task UID."));
            return 0;
        }
        boolean changed;
        if (blacklist) {
            changed = add ? service.addBlacklist(uid) : service.removeBlacklist(uid);
        } else {
            changed = add ? service.addWhitelist(uid) : service.removeWhitelist(uid);
        }
        String listName = blacklist ? "blacklist" : "whitelist";
        success(context, changed
                ? "%s %s %s".formatted(add ? "Added" : "Removed", uid, add ? "to " + listName : "from " + listName)
                : "%s is already %s the %s.".formatted(uid, add ? "in" : "absent from", listName));
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context, boolean blacklist) {
        AutoWorkCompatService service = getService(context);
        if (service == null) {
            return 0;
        }
        Set<String> entries = blacklist ? service.blacklist() : service.whitelist();
        String listName = blacklist ? "blacklist" : "whitelist";
        success(context, "Compatibility %s: %s".formatted(listName,
                entries.isEmpty() ? "(empty)" : String.join(", ", entries)));
        return 1;
    }

    private static int setReminder(CommandContext<CommandSourceStack> context,
                                   AutoWorkCompatService.ReminderLevel reminderLevel) {
        AutoWorkCompatService service = getService(context);
        if (service == null) {
            return 0;
        }
        service.setReminderLevel(reminderLevel);
        success(context, "Compatibility chat reminders set to " + reminderLevel + ".");
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        AutoWorkCompatService service = getService(context);
        if (service == null) {
            return 0;
        }
        service.reload();
        success(context, "Reloaded auto work compatibility policy. " + service.summary());
        return 1;
    }

    private static AutoWorkCompatService getService(CommandContext<CommandSourceStack> context) {
        AutoWorkCompatService service = AutoWorkCompatService.getOrNull(context.getSource().getServer());
        if (service == null) {
            context.getSource().sendFailure(Component.literal("Auto work compatibility service is not ready."));
        }
        return service;
    }

    private static ResourceLocation parseUid(CommandContext<CommandSourceStack> context) {
        String raw = UnicodeWordArgument.get(context, "uid");
        try {
            return ResourceLocation.parse(raw);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static void success(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), false);
    }
}
