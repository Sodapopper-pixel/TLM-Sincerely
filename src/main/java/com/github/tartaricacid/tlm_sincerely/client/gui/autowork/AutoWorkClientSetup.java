package com.github.tartaricacid.tlm_sincerely.client.gui.autowork;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.menu.AutoWorkMenus;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Client-only registrations for the auto work config UI.
 *
 * <p>Wires {@link RegisterMenuScreensEvent} for the standalone auto work
 * container, and the {@code ScreenEvent.Init.Post} overlap-correction pass
 * for our maid GUI top tab. Registering here (instead of from the common
 * {@code SincerelyExtension} constructor) keeps everything on the physical
 * client and avoids any risk of touching client-only classes from a server
 * runtime.
 */
@EventBusSubscriber(modid = SincerelyExtension.MOD_ID,
        value = Dist.CLIENT)
public final class AutoWorkClientSetup {
    private AutoWorkClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(AutoWorkMenus.AUTO_WORK_CONFIG.get(), AutoWorkConfigScreen::new);
    }

    /**
     * Second overlap-avoidance pass for the auto-work top tab.
     *
     * <p>{@link com.github.tartaricacid.tlm_sincerely.mixin.MaidTabsMixin}
     * can only see tabs appended by add-ons that run before it. By the time
     * NeoForge fires {@code ScreenEvent.Init.Post}, every widget is
     * registered — tabs from mixins that ran after ours, buttons added via
     * {@code MaidContainerGuiEvent.Init}, and TLM's own widgets — so we can
     * detect any remaining overlap and nudge our tab to a free slot. The
     * probe is idempotent: an already-clear tab resolves to its own slot.
     */
    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof AbstractMaidContainerGui<?> gui)) {
            return;
        }
        AutoWorkMaidTabButton tab = null;
        for (var child : event.getScreen().children()) {
            if (child instanceof AutoWorkMaidTabButton candidate) {
                tab = candidate;
                break;
            }
        }
        if (tab == null) {
            // We may have skipped adding the tab when the row was full.
            return;
        }
        var current = new AutoWorkTabPlacer.Rect(tab.getX(), tab.getY(), tab.getWidth(), tab.getHeight());
        var obstacles = AutoWorkTabPlacer.collectObstacles(event.getScreen(), tab);
        if (!AutoWorkTabPlacer.overlapsAny(current, obstacles)) {
            return;
        }
        int[] slot = AutoWorkTabPlacer.findFreeSlot(gui.getGuiLeft(), gui.getGuiTop(), obstacles);
        if (slot != null) {
            tab.setPosition(slot[0], slot[1]);
        }
    }
}
