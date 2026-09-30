package com.github.tartaricacid.tlm_sincerely.priority.autowork.network;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPreset;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPresetService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkStateService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AutoWorkSeedS2CPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AutoWorkSnapshotS2CPacket;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable helpers used by C2S packet handlers and server lifecycle hooks.
 *
 * <p>Centralises the server-thread send pattern, the {@code sendSnapshot}
 * call, the login seed copy and the shared preset payload sanitization
 * (push relay and login seed). All methods assume they are invoked from the
 * server thread.
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
        AutoWorkNetworking.sendToPlayer(player, new AutoWorkSnapshotS2CPacket(snapshot));
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
     * it has no private library file yet. The seed file is hand-editable, so
     * the payload goes through the same sanitization as the push relay:
     * unregistered task uids and malformed entries never reach the wire.
     */
    public static void sendSeed(ServerPlayer player) {
        if (player == null) {
            return;
        }
        AutoWorkPresetService presetService = AutoWorkPresetService.getOrNull(player.server);
        if (presetService == null) {
            return;
        }
        List<AutoWorkPresetData> raw = new ArrayList<>();
        for (AutoWorkPreset preset : presetService.listPresets()) {
            raw.add(AutoWorkPresetData.from(preset));
        }
        List<AutoWorkPresetData> payload = sanitizePresets(raw);
        AutoWorkNetworking.sendToPlayer(player,
                new AutoWorkSeedS2CPacket(presetService.getDefaultPresetId(), payload));
    }

    /**
     * Drops malformed payload entries and task uids this server does not
     * know, clamping names and order lengths to the wire caps. Shared by the
     * push relay and the login seed so both outbound preset paths stay
     * symmetric; a decoder exception closes the connection, so the encoder
     * side must be conservative.
     */
    public static List<AutoWorkPresetData> sanitizePresets(List<AutoWorkPresetData> payload) {
        List<AutoWorkPresetData> presets = new ArrayList<>();
        if (payload != null) {
            for (AutoWorkPresetData data : payload) {
                if (data == null || data.id() == null) {
                    continue;
                }
                String name = data.name() == null ? "" : data.name().trim();
                name = name.replace('\u00a7', ' ').trim();
                // 对齐 AutoWorkPresetData.encode 的 writeUtf(name, 64)：超长名字会让
                // 服务端编码抛异常并被 netty 断开连接（客户端侧表现为进服即被静默踢出）。
                if (name.length() > 64) {
                    name = name.substring(0, 64);
                }
                if (name.isEmpty()) {
                    continue;
                }
                // sanitizeOrder drops idle / null / duplicate entries and caps
                // the length; findTask then removes uids this server has no
                // task for (the client cannot resolve them either).
                List<ResourceLocation> order = new ArrayList<>();
                for (ResourceLocation task : AutoWorkStateService.sanitizeOrder(data.order())) {
                    if (TaskManager.findTask(task).isPresent()) {
                        order.add(task);
                    }
                }
                presets.add(new AutoWorkPresetData(data.id(), name, order));
                if (presets.size() >= AutoWorkPresetData.MAX_PRESETS) {
                    break;
                }
            }
        }
        return presets;
    }
}
