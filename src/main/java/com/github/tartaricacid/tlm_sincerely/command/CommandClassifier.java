package com.github.tartaricacid.tlm_sincerely.command;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.MaidCommandConfig;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Classifies a command against the blacklist and the confirmation list.
 *
 * <p>The decision is a double check (plan D4): the Brigadier parse tree node
 * names and a raw text token scan. Either source can trigger a decision.
 * Blacklist always wins over the confirmation list. The parse tree check is
 * best-effort: if parsing fails or does not resolve a node, the text scan is
 * still authoritative, so a command can never be silently downgraded by a
 * parse failure.
 */
public final class CommandClassifier {
    public enum Decision {
        ALLOW,
        CONFIRM,
        BLOCKED
    }

    public record Result(Decision decision, String matchedName) {
        public static final Result ALLOWED = new Result(Decision.ALLOW, "");
    }

    private CommandClassifier() {
    }

    public static Result classify(MinecraftServer server, String command) {
        Set<String> blacklist = normalizeAll(MaidCommandConfig.BLACKLISTED_COMMANDS.get());
        Set<String> confirmation = normalizeAll(MaidCommandConfig.CONFIRMATION_REQUIRED_COMMANDS.get());

        // Both judgement sources are merged into one candidate set so that
        // blacklist-first priority holds across sources.
        Set<String> candidates = new LinkedHashSet<>();
        candidates.addAll(collectTreeNames(server, command));
        candidates.addAll(scanTextNames(command));

        for (String name : candidates) {
            if (blacklist.contains(name)) {
                return new Result(Decision.BLOCKED, name);
            }
        }
        for (String name : candidates) {
            if (confirmation.contains(name)) {
                return new Result(Decision.CONFIRM, name);
            }
        }
        return Result.ALLOWED;
    }

    /**
     * Judgement one: literal node names of the Brigadier parse tree.
     *
     * <p>{@code execute ... run <command>} forks/redirects into a child
     * context; {@link ParseResults#getContext()} only exposes the outer
     * context, so the child chain must be walked recursively. Parse failures
     * are ignored here because judgement two covers them.
     */
    static Set<String> collectTreeNames(MinecraftServer server, String command) {
        Set<String> names = new LinkedHashSet<>();
        try {
            CommandSourceStack stack = server.createCommandSourceStack().withSuppressedOutput();
            ParseResults<CommandSourceStack> parsed =
                    server.getCommands().getDispatcher().parse(command, stack);
            collectContextNames(parsed.getContext(), names);
        } catch (Throwable ignored) {
            // Text scan is the fallback.
        }
        return names;
    }

    private static void collectContextNames(CommandContextBuilder<CommandSourceStack> context, Set<String> out) {
        if (context == null) {
            return;
        }
        for (ParsedCommandNode<CommandSourceStack> parsed : context.getNodes()) {
            if (parsed.getNode() instanceof LiteralCommandNode<CommandSourceStack>) {
                addNormalized(out, parsed.getNode().getName());
            }
        }
        collectContextNames(context.getChild(), out);
    }

    /**
     * Judgement two: raw text tokens, including the first token after every
     * {@code run} token. This also covers namespaced spellings such as
     * {@code /minecraft:kill} and commands Brigadier cannot parse.
     */
    static Set<String> scanTextNames(String command) {
        Set<String> names = new LinkedHashSet<>();
        String[] tokens = command.trim().split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String normalized = normalizeToken(tokens[i]);
            if (!normalized.isEmpty()) {
                names.add(normalized);
            }
            if ("run".equals(normalized) && i + 1 < tokens.length) {
                String afterRun = normalizeToken(tokens[i + 1]);
                if (!afterRun.isEmpty()) {
                    names.add(afterRun);
                }
            }
        }
        return names;
    }

    private static void addNormalized(Set<String> out, String raw) {
        String normalized = normalizeToken(raw);
        if (!normalized.isEmpty()) {
            out.add(normalized);
        }
    }

    private static Set<String> normalizeAll(Collection<? extends String> raw) {
        Set<String> normalized = new LinkedHashSet<>();
        if (raw == null) {
            return normalized;
        }
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            addNormalized(normalized, entry);
        }
        return normalized;
    }

    /**
     * Lowercases, drops leading slashes and strips a {@code namespace:}
     * prefix so that {@code minecraft:kill} and {@code kill} match the same
     * list entry.
     */
    static String normalizeToken(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        int colon = value.indexOf(':');
        if (colon > 0) {
            String prefix = value.substring(0, colon);
            if (isNamespaceLike(prefix)) {
                value = value.substring(colon + 1);
            }
        }
        return value;
    }

    private static boolean isNamespaceLike(String prefix) {
        if (prefix.isEmpty()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_' && c != '-' && c != '.') {
                return false;
            }
        }
        return true;
    }
}
