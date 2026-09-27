package com.github.tartaricacid.tlm_sincerely.client.gui;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.ChatBarConfig;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
import com.github.tartaricacid.touhoulittlemaid.api.event.client.AddClothConfigEvent;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.slf4j.Logger;

/** Adds TLM-Sincerely's categories to Touhou Little Maid's Cloth Config page. */
@EventBusSubscriber(modid = SincerelyExtension.MOD_ID, value = Dist.CLIENT)
public final class TlmConfigIntegration {
    private static final Logger LOGGER = LogUtils.getLogger();

    private TlmConfigIntegration() {
    }

    @SubscribeEvent
    public static void onAddClothConfig(AddClothConfigEvent event) {
        ConfigScreen.addEntries(event.getRoot(), event.getEntryBuilder());
        LOGGER.debug("Registered TLM-Sincerely config hierarchy in Touhou Little Maid: chatDefault={}, autoWorkDefault={}",
                ChatBarConfig.CHAT_MODE.getDefault(), PriorityConfig.ENABLED.getDefault());
    }
}
