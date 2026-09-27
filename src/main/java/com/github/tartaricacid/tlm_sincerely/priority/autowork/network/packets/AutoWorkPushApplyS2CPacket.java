package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import com.github.tartaricacid.tlm_sincerely.client.network.AutoWorkClientPayloadHandlers;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkPresetData;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * S2C: the payload of an accepted preset push. The client writes it into its
 * private library (UUID-preserving) and reports a local summary.
 */
public record AutoWorkPushApplyS2CPacket(List<AutoWorkPresetData> presets) implements CustomPacketPayload {
    public static final Type<AutoWorkPushApplyS2CPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SincerelyExtension.MOD_ID, "auto_work/push_apply"));

    public static final StreamCodec<ByteBuf, AutoWorkPushApplyS2CPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AutoWorkPushApplyS2CPacket decode(ByteBuf buf) {
            return AutoWorkPushApplyS2CPacket.decode(new FriendlyByteBuf(buf));
        }

        @Override
        public void encode(ByteBuf buf, AutoWorkPushApplyS2CPacket msg) {
            AutoWorkPushApplyS2CPacket.encode(msg, new FriendlyByteBuf(buf));
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(AutoWorkPushApplyS2CPacket msg, FriendlyByteBuf buf) {
        AutoWorkPresetData.encodeList(buf, msg.presets);
    }

    public static AutoWorkPushApplyS2CPacket decode(FriendlyByteBuf buf) {
        return new AutoWorkPushApplyS2CPacket(AutoWorkPresetData.decodeList(buf));
    }

    public static void handle(AutoWorkPushApplyS2CPacket msg, IPayloadContext context) {
        if (context.flow().isClientbound()) {
            AutoWorkClientPayloadHandlers.handlePushApply(msg);
        }
    }
}
