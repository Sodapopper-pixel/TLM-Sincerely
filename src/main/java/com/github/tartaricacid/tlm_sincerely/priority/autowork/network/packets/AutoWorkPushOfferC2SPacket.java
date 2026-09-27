package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkPresetData;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.push.AutoWorkPushService;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * C2S: a preset push prepared by the sender's client command. The server
 * validates the targets / permissions and relays the payload; nothing is
 * written until each receiver confirms in chat.
 */
public record AutoWorkPushOfferC2SPacket(boolean broadcast, String targetName,
                                         List<AutoWorkPresetData> presets)
        implements CustomPacketPayload {
    public static final Type<AutoWorkPushOfferC2SPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SincerelyExtension.MOD_ID, "auto_work/push_offer"));

    private static final Logger LOGGER = LoggerFactory.getLogger(AutoWorkPushOfferC2SPacket.class);

    public static final StreamCodec<ByteBuf, AutoWorkPushOfferC2SPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AutoWorkPushOfferC2SPacket decode(ByteBuf buf) {
            return AutoWorkPushOfferC2SPacket.decode(new FriendlyByteBuf(buf));
        }

        @Override
        public void encode(ByteBuf buf, AutoWorkPushOfferC2SPacket msg) {
            AutoWorkPushOfferC2SPacket.encode(msg, new FriendlyByteBuf(buf));
        }
    };

    public AutoWorkPushOfferC2SPacket {
        targetName = targetName == null ? "" : targetName;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(AutoWorkPushOfferC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.broadcast);
        buf.writeUtf(msg.targetName, 64);
        AutoWorkPresetData.encodeList(buf, msg.presets);
    }

    public static AutoWorkPushOfferC2SPacket decode(FriendlyByteBuf buf) {
        boolean broadcast = buf.readBoolean();
        String targetName = buf.readUtf(64);
        return new AutoWorkPushOfferC2SPacket(broadcast, targetName, AutoWorkPresetData.decodeList(buf));
    }

    public static void handle(AutoWorkPushOfferC2SPacket msg, IPayloadContext context) {
        ServerPlayer sender = context.player() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (sender == null) {
            return;
        }
        AutoWorkPushService service = AutoWorkPushService.getOrNull(sender.server);
        if (service == null) {
            return;
        }
        try {
            service.handleOffer(sender, msg.broadcast, msg.targetName, msg.presets);
        } catch (RuntimeException exception) {
            LOGGER.warn("[AutoWorkPush] failed to handle push for {}: {}",
                    sender.getName().getString(), exception.getMessage());
        }
    }
}
