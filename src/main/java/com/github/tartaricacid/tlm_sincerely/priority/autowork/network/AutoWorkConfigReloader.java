package com.github.tartaricacid.tlm_sincerely.priority.autowork.network;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Rebroadcasts auto work snapshots after the mod's COMMON config reloads.
 *
 * <p>{@code PriorityConfig.ENABLED} gates the scheduler on the server side
 * but is not synced to clients by either loader; clients read it from the
 * auto work snapshot ({@link AutoWorkSnapshot#globalEnabled()}). Reloading
 * the config while players are online would leave every client showing a
 * stale global switch state, so this listener pushes fresh snapshots once
 * the new values are live.
 *
 * <p>ModConfigEvent is an IModBusEvent that is only ever published to this
 * mod's own container MOD bus, so no mod-id check is needed. Reloading fires
 * from the config file watcher thread after the spec already accepted the
 * new values; snapshot building touches world state, hence the
 * {@code server.execute} hop onto the server thread.
 */
@Mod.EventBusSubscriber(modid = SincerelyExtension.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
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
