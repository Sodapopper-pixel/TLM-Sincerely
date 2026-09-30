package com.github.tartaricacid.tlm_sincerely.chatbar;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.ChatBarConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

/**
 * 聊天栏「女仆对话模式 / 全局可见性」的玩家级运行时开关。
 *
 * <p>此前 /tlmchat mode|global 直接写 COMMON 配置（专用服务器上全服共享且落盘），
 * 任何玩家都能修改全服行为；现改为随玩家 {@link ServerPlayer#getPersistentData()}
 * 持久化的独立状态。ChatBarConfig 中的 ChatModeEnabled / GlobalChatVisible 仅作为
 * 新玩家（尚无个人记录时）的默认值，不再被命令改写。
 */
public final class ChatBarPlayerState {
    /** PersistentData 中的键，带 modid 前缀避免与其他模组冲突 */
    private static final String KEY_CHAT_MODE = "tlm_sincerely:chat_mode";
    private static final String KEY_GLOBAL_VISIBLE = "tlm_sincerely:global_visible";

    private ChatBarPlayerState() {
    }

    /** 读取玩家的女仆对话模式；无个人记录时回退到 COMMON 配置的默认值 */
    public static boolean isChatModeEnabled(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (data.contains(KEY_CHAT_MODE, Tag.TAG_BYTE)) {
            return data.getBoolean(KEY_CHAT_MODE);
        }
        return ChatBarConfig.CHAT_MODE.get();
    }

    /** 写入玩家的女仆对话模式（随玩家 NBT 持久化） */
    public static void setChatModeEnabled(ServerPlayer player, boolean enabled) {
        player.getPersistentData().putBoolean(KEY_CHAT_MODE, enabled);
    }

    /** 读取玩家自己的全局/私聊可见性偏好；无个人记录时回退到 COMMON 配置的默认值 */
    public static boolean isGlobalVisible(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (data.contains(KEY_GLOBAL_VISIBLE, Tag.TAG_BYTE)) {
            return data.getBoolean(KEY_GLOBAL_VISIBLE);
        }
        return ChatBarConfig.GLOBAL_VISIBLE.get();
    }

    /** 写入玩家自己的全局/私聊可见性偏好（随玩家 NBT 持久化） */
    public static void setGlobalVisible(ServerPlayer player, boolean globalVisible) {
        player.getPersistentData().putBoolean(KEY_GLOBAL_VISIBLE, globalVisible);
    }
}
