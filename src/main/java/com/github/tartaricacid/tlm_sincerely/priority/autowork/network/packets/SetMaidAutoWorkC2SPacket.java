package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkStateService;
import com.github.tartaricacid.tlm_sincerely.priority.TaskAutoSwitchHandler;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkPermission;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkServerHandler;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/** C2S: enable or disable the auto work switch for a single maid. */
public record SetMaidAutoWorkC2SPacket(UUID maidId, boolean enabled) implements CustomPacketPayload {
    public static final Type<SetMaidAutoWorkC2SPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SincerelyExtension.MOD_ID, "auto_work/set"));

    private static final Logger LOGGER = LoggerFactory.getLogger(SetMaidAutoWorkC2SPacket.class);

    // Mirrors the removed ByteBufCodecs.UUID_STREAM_CODEC, which only exists
    // on newer Minecraft versions; 1.21.1 ships the static FriendlyByteBuf
    // helpers instead.
    private static final StreamCodec<ByteBuf, UUID> UUID_STREAM_CODEC =
            StreamCodec.of(FriendlyByteBuf::writeUUID, FriendlyByteBuf::readUUID);

    public static final StreamCodec<ByteBuf, SetMaidAutoWorkC2SPacket> STREAM_CODEC = StreamCodec.composite(
            UUID_STREAM_CODEC, SetMaidAutoWorkC2SPacket::maidId,
            ByteBufCodecs.BOOL, SetMaidAutoWorkC2SPacket::enabled,
            SetMaidAutoWorkC2SPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetMaidAutoWorkC2SPacket msg, IPayloadContext context) {
        var sender = context.player() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (sender == null) {
            return;
        }
        EntityMaid maid = AutoWorkPermission.resolveMaid(sender, msg.maidId);
        if (maid == null) {
            LOGGER.debug("[AutoWork] SetMaidAutoWork: unknown maid {}", msg.maidId);
            return;
        }
        if (!AutoWorkPermission.canControlMaid(sender, maid)) {
            LOGGER.info("[AutoWork] SetMaidAutoWork denied for {} on maid {}",
                    sender.getName().getString(), msg.maidId);
            return;
        }
        AutoWorkStateService stateService =
                AutoWorkStateService.getOrNull(sender.server);
        if (stateService == null) {
            return;
        }
        stateService.setEnabled(maid, msg.enabled);
        if (msg.enabled) {
            TaskAutoSwitchHandler.requestImmediateEvaluation(maid, "PLAYER_ENABLE", true);
        }
        LOGGER.debug("[AutoWork] SetMaidAutoWork maid={} enabled={}", maid.getUUID(), msg.enabled);
        AutoWorkServerHandler.sendSnapshot(sender);
    }
}
