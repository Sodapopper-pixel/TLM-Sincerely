package com.github.tartaricacid.tlm_sincerely.priority.autowork.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AutoWorkPushApplyS2CPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AutoWorkPushOfferC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AutoWorkSeedS2CPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AutoWorkSnapshotS2CPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.OpenAutoWorkConfigC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.RequestAutoWorkSnapshotC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.SetMaidAutoWorkC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.SetMaidAutoWorkPresetC2SPacket;

/**
 * Registers the auto work switch payloads.
 *
 * <p>Preset library edits are client-local and no longer travel over the
 * network; the wire format only carries maid bound snapshots, compat entries,
 * the login seed and preset pushes. The protocol version is bumped whenever
 * that format changes in a backward-incompatible way.
 */
public final class AutoWorkNetworking {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoWorkNetworking.class);
    public static final String PROTOCOL_VERSION = "3";

    private AutoWorkNetworking() {
    }

    /** Called from the SincerelyMod constructor: binds the mod bus listener. */
    public static void register(IEventBus modBus) {
        modBus.addListener(AutoWorkNetworking::onRegisterPayloadHandlers);
    }

    @SubscribeEvent
    public static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        // S2C: playToClient only ships the three-arg overload on 1.21; the
        // handlers resolve client-only logic lazily from inside the payload
        // classes (guarded by the packet flow check), so loading them on a
        // dedicated server stays safe.
        registrar.playToClient(AutoWorkSnapshotS2CPacket.TYPE, AutoWorkSnapshotS2CPacket.STREAM_CODEC,
                AutoWorkSnapshotS2CPacket::handle);
        registrar.playToClient(AutoWorkSeedS2CPacket.TYPE, AutoWorkSeedS2CPacket.STREAM_CODEC,
                AutoWorkSeedS2CPacket::handle);
        registrar.playToClient(AutoWorkPushApplyS2CPacket.TYPE, AutoWorkPushApplyS2CPacket.STREAM_CODEC,
                AutoWorkPushApplyS2CPacket::handle);
        // C2S
        registrar.playToServer(RequestAutoWorkSnapshotC2SPacket.TYPE, RequestAutoWorkSnapshotC2SPacket.STREAM_CODEC,
                RequestAutoWorkSnapshotC2SPacket::handle);
        registrar.playToServer(SetMaidAutoWorkC2SPacket.TYPE, SetMaidAutoWorkC2SPacket.STREAM_CODEC,
                SetMaidAutoWorkC2SPacket::handle);
        registrar.playToServer(SetMaidAutoWorkPresetC2SPacket.TYPE, SetMaidAutoWorkPresetC2SPacket.STREAM_CODEC,
                SetMaidAutoWorkPresetC2SPacket::handle);
        registrar.playToServer(OpenAutoWorkConfigC2SPacket.TYPE, OpenAutoWorkConfigC2SPacket.STREAM_CODEC,
                OpenAutoWorkConfigC2SPacket::handle);
        registrar.playToServer(AutoWorkPushOfferC2SPacket.TYPE, AutoWorkPushOfferC2SPacket.STREAM_CODEC,
                AutoWorkPushOfferC2SPacket::handle);

        LOGGER.info("[AutoWorkNetworking] registered auto_work payloads protocol={}", PROTOCOL_VERSION);
    }

    /** Server-side send helper (used by AutoWorkServerHandler / AutoWorkPushService). */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendToAllPlayers(CustomPacketPayload payload) {
        PacketDistributor.sendToAllPlayers(payload);
    }
}
