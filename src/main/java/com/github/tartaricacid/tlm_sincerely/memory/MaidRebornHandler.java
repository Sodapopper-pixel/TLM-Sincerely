package com.github.tartaricacid.tlm_sincerely.memory;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidAndItemTransformEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;

/**
 * 神龛/胶片复活补救：主模组复活生成新实体（新 UUID），且不保留命名牌。
 * 在 {@code MaidAndItemTransformEvent.ToMaid} 中做两件事：
 * <ol>
 * <li>按胶片 {@code MaidInfo.UUID}（原 UUID）把记忆文件迁移到新 UUID，旧文件保留作备份；</li>
 * <li>按胶片 {@code MaidInfo.CustomName} 回填命名牌，恢复寻址/Jade/AI 路由。</li>
 * </ol>
 * 祭坛配方路径不抛此事件，暂不覆盖。
 */
@Mod.EventBusSubscriber
public final class MaidRebornHandler {
    private static final Logger LOGGER = LogManager.getLogger("TLM_Sincerely/Reborn");

    private MaidRebornHandler() {
    }

    @SubscribeEvent
    public static void onMaidReborn(MaidAndItemTransformEvent.ToMaid event) {
        if (event.getMaid().level().isClientSide()) {
            return;
        }
        CompoundTag data = event.getData();
        if (data == null || !data.hasUUID("UUID")) {
            return;
        }
        UUID oldUuid = data.getUUID("UUID");
        UUID newUuid = event.getMaid().getUUID();
        if (oldUuid.equals(newUuid)) {
            return;
        }

        boolean migrated = MaidMemoryManager.migrate(oldUuid, newUuid);
        if (!migrated) {
            LOGGER.warn("Memory migration failed for reborn maid {} -> {}", oldUuid, newUuid);
        }

        if (data.contains("CustomName", 8)) {
            String customName = data.getString("CustomName");
            if (!customName.isEmpty()) {
                try {
                    Component name = Component.Serializer.fromJson(customName);
                    if (name != null) {
                        event.getMaid().setCustomName(name);
                        event.getMaid().setCustomNameVisible(true);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to restore custom name for reborn maid {}", newUuid, e);
                }
            }
        }
    }
}
