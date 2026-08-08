package com.github.tartaricacid.tlm_sincerely.client.gui.autowork;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.menu.AutoWorkMenus;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Client-only registrations for the auto work config UI.
 *
 * <p>Currently this only wires
 * {@link net.minecraft.client.gui.screens.MenuScreens#register} for the
 * standalone auto work container. Registering the screen here (instead
 * of from the common {@code SincerelyExtension} constructor) keeps the
 * registry call on the physical client and avoids any risk of touching
 * client-only classes from a server runtime.
 */
@EventBusSubscriber(modid = SincerelyExtension.MOD_ID,
        bus = EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT)
public final class AutoWorkClientSetup {
    private AutoWorkClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // enqueueWork runs the registration on the main client thread,
        // which is what MenuScreens.register requires.
        event.enqueueWork(() -> net.minecraft.client.gui.screens.MenuScreens.register(
                AutoWorkMenus.AUTO_WORK_CONFIG.get(),
                AutoWorkConfigScreen::new));
    }
}
