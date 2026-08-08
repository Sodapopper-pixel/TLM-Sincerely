package com.github.tartaricacid.tlm_sincerely.client.gui.autowork;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import com.github.tartaricacid.tlm_sincerely.client.network.ClientAutoWorkService;
import com.github.tartaricacid.touhoulittlemaid.api.event.client.MaidContainerGuiEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Adds the per-maid auto-work toggle below the portrait without touching TLM's task list. */
@Mod.EventBusSubscriber(modid = SincerelyExtension.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class AutoWorkToggleButtonController {
    private AutoWorkToggleButtonController() {
    }

    @SubscribeEvent
    public static void onMaidGuiInit(MaidContainerGuiEvent.Init event) {
        EntityMaid maid = event.getGui().getMaid();
        if (maid == null) {
            return;
        }
        ClientAutoWorkService.get().requestRefresh();
        // 76 px equals the portrait-panel width and ends just before the
        // right configuration area at leftPos + 80.
        event.addButton("tlm_sincerely_auto_work_toggle", new AutoWorkVirtualTaskButton(
                event.getLeftPos() + 2, event.getTopPos() + 253, 76, 19, maid.getUUID()));
    }
}
