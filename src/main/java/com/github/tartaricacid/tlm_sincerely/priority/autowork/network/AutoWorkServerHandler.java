package com.github.tartaricacid.tlm_sincerely.priority.autowork.network;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AutoWorkSnapshotS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

/**
 * Reusable helpers used by C2S packet handlers (T-2 A3).
 *
 * <p>Centralises the {@code enqueueWork} → server-thread pattern and the
 * {@code sendSnapshot} call so packet handlers stay short and uniform.
 * All methods assume they are invoked from the server thread.
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

    /** Sends fresh preset/state snapshots to every connected player. */
    public static void broadcastSnapshots(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendSnapshot(player);
        }
    }
}
