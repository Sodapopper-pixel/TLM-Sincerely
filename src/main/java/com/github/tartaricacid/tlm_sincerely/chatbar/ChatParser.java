package com.github.tartaricacid.tlm_sincerely.chatbar;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerPlayer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatParser {
    private static final Pattern AT_PATTERN = Pattern.compile("^@(.+?)\\s+(.+)$");

    public static ChatTarget parse(ServerPlayer player, String message) {
        Matcher matcher = AT_PATTERN.matcher(message);

        if (matcher.matches()) {
            String name = matcher.group(1);
            String content = matcher.group(2);
            EntityMaid maid = MaidFinder.findByName(player, name);
            return new ChatTarget(maid, content, true);
        }

        EntityMaid maid = MaidFinder.findNearest(player);
        return new ChatTarget(maid, message, false);
    }
}