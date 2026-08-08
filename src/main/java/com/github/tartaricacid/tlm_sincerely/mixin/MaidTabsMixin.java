package com.github.tartaricacid.tlm_sincerely.mixin;

import com.github.tartaricacid.tlm_sincerely.client.gui.autowork.AutoWorkConfigScreen;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkNetworking;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.OpenAutoWorkConfigC2SPacket;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.MaidTabs;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.MaidTabButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

/** Adds the standalone auto-work configuration entry to TLM's fourth top tab slot. */
@Mixin(value = MaidTabs.class, remap = true)
public class MaidTabsMixin {
    private static final int AUTO_WORK_TAB_SLOT = 3;
    private static final int TAB_X_OFFSET = 94;
    private static final int TAB_SPACING = 25;
    /** The unused fourth icon column in TLM's maid_gui_side.png tab strip. */
    private static final int TAB_TEXTURE_LEFT = 182;

    @Inject(method = "getTabs", at = @At("RETURN"), cancellable = true, remap = false)
    private void tlmSincerely$appendAutoWorkTab(AbstractMaidContainerGui<?> screen,
                                                CallbackInfoReturnable<MaidTabButton[]> cir) {
        EntityMaid maid = screen.getMaid();
        MaidTabButton[] original = cir.getReturnValue();
        if (maid == null || original == null || original.length > AUTO_WORK_TAB_SLOT) {
            return;
        }

        MaidTabButton autoWorkTab = new MaidTabButton(
                screen.getGuiLeft() + TAB_X_OFFSET + TAB_SPACING * AUTO_WORK_TAB_SLOT,
                screen.getGuiTop() + 5,
                TAB_TEXTURE_LEFT,
                "tlm_sincerely_auto_work",
                button -> AutoWorkNetworking.channel().sendToServer(
                        new OpenAutoWorkConfigC2SPacket(maid.getUUID())));
        // Match TLM's selected-tab appearance and prevent reopening the same menu.
        autoWorkTab.active = !(screen instanceof AutoWorkConfigScreen);

        MaidTabButton[] extended = Arrays.copyOf(original, original.length + 1);
        extended[original.length] = autoWorkTab;
        cir.setReturnValue(extended);
    }
}
