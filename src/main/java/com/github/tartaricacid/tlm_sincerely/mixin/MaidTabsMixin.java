package com.github.tartaricacid.tlm_sincerely.mixin;

import com.github.tartaricacid.tlm_sincerely.client.gui.autowork.AutoWorkConfigScreen;
import com.github.tartaricacid.tlm_sincerely.client.gui.autowork.AutoWorkMaidTabButton;
import com.github.tartaricacid.tlm_sincerely.client.gui.autowork.AutoWorkTabPlacer;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.OpenAutoWorkConfigC2SPacket;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.MaidTabs;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.MaidTabButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.logging.LogUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;

/**
 * Adds the standalone auto-work configuration entry to TLM's top tab row.
 *
 * <p>Other add-ons may append their own tabs to {@code getTabs} at the
 * same fourth-slot position. Instead of blindly taking slot 3 (or giving
 * up when the array is already longer), we probe TLM's tab row for the
 * first overlap-free slot against the buttons already present. Tabs
 * appended by add-ons that run after this mixin are handled separately
 * by the {@code ScreenEvent.Init.Post} pass in {@code AutoWorkClientSetup},
 * which runs once every widget is registered.
 */
@Mixin(value = MaidTabs.class, remap = true)
public class MaidTabsMixin {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Only the selected-tab background slice is reused; the icon is our own. */
    private static final int TAB_BACKGROUND_LEFT = 182;

    @Inject(method = "getTabs", at = @At("RETURN"), cancellable = true, remap = false)
    private void tlmSincerely$appendAutoWorkTab(AbstractMaidContainerGui<?> screen,
                                                CallbackInfoReturnable<MaidTabButton[]> cir) {
        EntityMaid maid = screen.getMaid();
        MaidTabButton[] original = cir.getReturnValue();
        if (maid == null || original == null) {
            return;
        }

        List<AutoWorkTabPlacer.Rect> obstacles = new ArrayList<>();
        for (MaidTabButton tab : original) {
            obstacles.add(new AutoWorkTabPlacer.Rect(tab.getX(), tab.getY(), tab.getWidth(), tab.getHeight()));
        }
        int[] slot = AutoWorkTabPlacer.findFreeSlot(screen.getGuiLeft(), screen.getGuiTop(), obstacles);
        if (slot == null) {
            LOGGER.debug("Auto-work tab skipped: every candidate slot on the tab row is occupied");
            return;
        }

        MaidTabButton autoWorkTab = new AutoWorkMaidTabButton(
                slot[0], slot[1],
                TAB_BACKGROUND_LEFT,
                "tlm_sincerely_auto_work",
                button -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new OpenAutoWorkConfigC2SPacket(maid.getUUID())));
        // Match TLM's selected-tab appearance and prevent reopening the same menu.
        autoWorkTab.active = !(screen instanceof AutoWorkConfigScreen);

        MaidTabButton[] extended = Arrays.copyOf(original, original.length + 1);
        extended[original.length] = autoWorkTab;
        cir.setReturnValue(extended);
        LOGGER.debug("Added custom auto-work tab at x={}, y={} (existingTabs={})",
                slot[0], slot[1], original.length);
    }
}
