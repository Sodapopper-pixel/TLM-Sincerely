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
import java.util.UUID;

/**
 * S2C: the server's frozen seed library, sent once on login. The client only
 * imports it when it has no local library file yet, so the seed never
 * overwrites a private library.
 */
public record AutoWorkSeedS2CPacket(UUID defaultPresetId, List<AutoWorkPresetData> presets)
        implements CustomPacketPayload {
    public static final Type<AutoWorkSeedS2CPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SincerelyExtension.MOD_ID, "auto_work/seed"));

    public static final StreamCodec<ByteBuf, AutoWorkSeedS2CPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AutoWorkSeedS2CPacket decode(ByteBuf buf) {
            return AutoWorkSeedS2CPacket.decode(new FriendlyByteBuf(buf));
        }

        @Override
        public void encode(ByteBuf buf, AutoWorkSeedS2CPacket msg) {
            AutoWorkSeedS2CPacket.encode(msg, new FriendlyByteBuf(buf));
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(AutoWorkSeedS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.defaultPresetId);
        AutoWorkPresetData.encodeList(buf, msg.presets);
    }

    public static AutoWorkSeedS2CPacket decode(FriendlyByteBuf buf) {
        UUID defaultId = buf.readUUID();
        return new AutoWorkSeedS2CPacket(defaultId, AutoWorkPresetData.decodeList(buf));
    }

    public static void handle(AutoWorkSeedS2CPacket msg, IPayloadContext context) {
        if (context.flow().isClientbound()) {
            AutoWorkClientPayloadHandlers.handleSeed(msg);
        }
    }
}
