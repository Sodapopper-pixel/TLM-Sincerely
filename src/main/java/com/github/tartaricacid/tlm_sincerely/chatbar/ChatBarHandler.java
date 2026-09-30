package com.github.tartaricacid.tlm_sincerely.chatbar;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.ChatBarConfig;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mod.EventBusSubscriber
public final class ChatBarHandler {
    private static final String DEFAULT_LANGUAGE = "en_us";
    private static final Pattern AT_PATTERN = Pattern.compile("^@(.+?)\\s+(.+)$");
    private static volatile String cachedPrefix = "";
    private static volatile Pattern cachedPrefixPattern;

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (!ChatBarPlayerState.isChatModeEnabled(player)) {
            return;
        }

        String rawMessage = event.getRawText();

        String prefix = ChatBarConfig.PREFIX_PATTERN.get();
        EntityMaid targetMaid = null;
        String chatMessage = rawMessage;
        boolean prefixUsed = false;

        // 1. Always try explicit prefix first.
        if (prefix != null && !prefix.isEmpty()) {
            Pattern pattern = getPrefixPattern(prefix);
            Matcher matcher = pattern.matcher(rawMessage);
            if (matcher.find()) {
                prefixUsed = true;
                String name = matcher.group(1);
                chatMessage = matcher.group(2);
                MaidFinder.FindResult result = MaidFinder.findByName(player, name);
                if (result.hasMaid()) {
                    targetMaid = result.maid();
                    if (result.hasMultipleMatches()) {
                        String uuidShort = targetMaid.getUUID().toString().substring(0, 8);
                        player.sendSystemMessage(Component.translatable(
                                "chat.tlm_sincerely.multiple_same_name", name, uuidShort
                        ).withStyle(ChatFormatting.YELLOW));
                    }
                } else {
                    player.sendSystemMessage(Component.translatable("chat.tlm_sincerely.maid_not_found")
                            .withStyle(ChatFormatting.RED));
                }
            }
        }

        // 2. No-prefix message + REQUIRE_PREFIX=false → auto-match nearest maid
        // ONLY when the configured prefix was not explicitly used.
        if (targetMaid == null && !ChatBarConfig.REQUIRE_PREFIX.get() && !prefixUsed) {
            double range = ChatBarConfig.AUTO_CHAT_RANGE.get();
            if (range > 0) {
                List<EntityMaid> maids = MaidFinder.getOwnedMaids(player, range);
                if (!maids.isEmpty()) {
                    targetMaid = MaidFinder.findNearest(player, maids);
                }
            }
        }

        if (targetMaid != null) {
            sendChatToMaid(targetMaid, player, chatMessage);

            // 是否取消原始聊天消息由该玩家自己的私聊可见性偏好决定
            if (!ChatBarPlayerState.isGlobalVisible(player)) {
                event.setCanceled(true);
                String format = "<%s -> %s> %s".formatted(
                        player.getScoreboardName(),
                        targetMaid.getName().getString(),
                        chatMessage
                );
                player.sendSystemMessage(Component.literal(format).withStyle(ChatFormatting.GRAY));
            }
        }
    }

    private static Pattern getPrefixPattern(String prefix) {
        Pattern pattern = cachedPrefixPattern;
        if (pattern != null && prefix.equals(cachedPrefix)) {
            return pattern;
        }
        synchronized (ChatBarHandler.class) {
            pattern = cachedPrefixPattern;
            if (pattern != null && prefix.equals(cachedPrefix)) {
                return pattern;
            }
            cachedPrefix = prefix;
            cachedPrefixPattern = Pattern.compile("^" + Pattern.quote(prefix) + "(.+?)\\s+(.+)$");
            return cachedPrefixPattern;
        }
    }

    private static void sendChatToMaid(EntityMaid maid, ServerPlayer player, String message) {
        String language = maid.getAiChatManager().getTTSLanguage();
        if (language == null || language.isEmpty()) {
            language = DEFAULT_LANGUAGE;
        }
        String name = maid.getName().getString();
        List<String> description = List.of();
        ChatClientInfo clientInfo = new ChatClientInfo(language, name, description);
        maid.getAiChatManager().chat(message, clientInfo, player);
    }
}
