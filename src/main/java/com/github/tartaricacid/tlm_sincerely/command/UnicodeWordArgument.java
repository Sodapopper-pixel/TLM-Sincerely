package com.github.tartaricacid.tlm_sincerely.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.Collection;
import java.util.List;

/**
 * 自定义参数类型：读取直到遇空格为止的连续字符，支持任意 Unicode 字符（含中文）。
 * 解决 Minecraft 1.20.1 Brigadier 1.1.8 中 readUnquotedString 不支持非 ASCII 的问题。
 */
public class UnicodeWordArgument implements ArgumentType<String> {
    private final String name;

    private UnicodeWordArgument(String name) {
        this.name = name;
    }

    public static UnicodeWordArgument word(String name) {
        return new UnicodeWordArgument(name);
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        final int start = reader.getCursor();
        while (reader.canRead() && reader.peek() != ' ') {
            reader.skip();
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    @Override
    public Collection<String> getExamples() {
        return List.of("word", "中文名", "name_with_underscores");
    }

    public static String get(CommandContext<?> ctx, String name) {
        return ctx.getArgument(name, String.class);
    }
}
