package com.github.tartaricacid.tlm_sincerely.priority.autowork.network;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Rebroadcasts auto work snapshots after the mod's COMMON config reloads.
 *
 * <p>{@code PriorityConfig.ENABLED} gates the scheduler on the server side
 * but is not synced to clients by NeoForge; clients read it from the auto
 * work snapshot ({@link AutoWorkSnapshot#globalEnabled()}). Reloading the
 * config while players are online would leave every client showing a stale
 * global switch state, so this listener pushes fresh snapshots once the new
 * values are live.
 *
 * <p>ModConfigEvent is a MOD-bus event that is only ever published to this
 * mod's own container bus, so no mod-id check is needed. Reloading fires
 * from the config file watcher thread after the spec already accepted the
 * new values; snapshot building touches world state, hence the
 * {@code server.execute} hop onto the server thread.
 */
@EventBusSubscriber(modid = SincerelyExtension.MOD_ID)
public final class AutoWorkConfigReloader {
    private AutoWorkConfigReloader() {
    }

    @SubscribeEvent
    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getType() != ModConfig.Type.COMMON) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.execute(() -> AutoWorkServerHandler.broadcastSnapshots(server));
        }
    }
}
