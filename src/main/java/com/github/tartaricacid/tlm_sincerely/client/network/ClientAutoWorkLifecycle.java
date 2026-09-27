package com.github.tartaricacid.tlm_sincerely.client.network;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;

/**
 * Clears the client-side auto work cache when the player logs out
 * (T-2 A3).
 */
@EventBusSubscriber(modid = SincerelyExtension.MOD_ID, value = Dist.CLIENT)
public final class ClientAutoWorkLifecycle {
    private ClientAutoWorkLifecycle() {
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientAutoWorkService.get().clear();
    }
}
