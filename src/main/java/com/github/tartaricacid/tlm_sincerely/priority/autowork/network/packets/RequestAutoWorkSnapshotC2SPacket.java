package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkServerHandler;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** C2S: request a fresh snapshot. No payload. */
public record RequestAutoWorkSnapshotC2SPacket() implements CustomPacketPayload {
    public static final Type<RequestAutoWorkSnapshotC2SPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SincerelyExtension.MOD_ID, "auto_work/request_snapshot"));

    public static final RequestAutoWorkSnapshotC2SPacket INSTANCE = new RequestAutoWorkSnapshotC2SPacket();

    public static final StreamCodec<ByteBuf, RequestAutoWorkSnapshotC2SPacket> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestAutoWorkSnapshotC2SPacket msg, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender) {
            AutoWorkServerHandler.sendSnapshot(sender);
        }
    }
}
