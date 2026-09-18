package com.github.tartaricacid.tlm_sincerely.priority.autowork.network;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPreset;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPresetService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AutoWorkSeedS2CPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AutoWorkSnapshotS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable helpers used by C2S packet handlers and server lifecycle hooks.
 *
 * <p>Centralises the {@code enqueueWork} → server-thread pattern, the
 * {@code sendSnapshot} call and the login seed copy. All methods assume they
 * are invoked from the server thread.
 */
public final class AutoWorkServerHandler {
    private AutoWorkServerHandler() {
    }

    /**
     * Builds a fresh snapshot for {@code player} and dispatches it. Safe
     * to call from the server thread; does nothing if any argument is
     * null.
     */
    public static void sendSnapshot(ServerPlayer player) {
        if (player == null) {
            return;
        }
        AutoWorkSnapshot snapshot = AutoWorkSnapshotBuilder.build(player.server, player);
        AutoWorkNetworking.channel().send(
                PacketDistributor.PLAYER.with(() -> player),
                new AutoWorkSnapshotS2CPacket(snapshot)
        );
    }

    /** Sends fresh maid/compat snapshots to every connected player. */
    public static void broadcastSnapshots(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendSnapshot(player);
        }
    }

    /**
     * Sends the frozen seed library on login. The client imports it only when
     * it has no private library file yet.
     */
    public static void sendSeed(ServerPlayer player) {
        if (player == null) {
            return;
        }
        AutoWorkPresetService presetService = AutoWorkPresetService.getOrNull(player.server);
        if (presetService == null) {
            return;
        }
        List<AutoWorkPresetData> payload = new ArrayList<>();
        for (AutoWorkPreset preset : presetService.listPresets()) {
            payload.add(AutoWorkPresetData.from(preset));
        }
        AutoWorkNetworking.channel().send(
                PacketDistributor.PLAYER.with(() -> player),
                new AutoWorkSeedS2CPacket(presetService.getDefaultPresetId(), payload)
        );
    }
}
