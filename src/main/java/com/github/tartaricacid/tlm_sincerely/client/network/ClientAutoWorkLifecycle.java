package com.github.tartaricacid.tlm_sincerely.client.network;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;

/**
 * Clears the client-side auto work cache when the player logs out
 * (T-2 A3).
 */
@EventBusSubscriber(modid = SincerelyExtension.MOD_ID, bus = EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientAutoWorkLifecycle {
    private ClientAutoWorkLifecycle() {
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientAutoWorkService.get().clear();
    }
}
