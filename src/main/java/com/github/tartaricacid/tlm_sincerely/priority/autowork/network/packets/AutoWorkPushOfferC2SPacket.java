package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkPresetData;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.push.AutoWorkPushService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * C2S: a preset push prepared by the sender's client command. The server
 * validates the targets / permissions and relays the payload; nothing is
 * written until each receiver confirms in chat.
 */
public final class AutoWorkPushOfferC2SPacket {
    public static final int INDEX = 13;
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoWorkPushOfferC2SPacket.class);

    private final boolean broadcast;
    private final String targetName;
    private final List<AutoWorkPresetData> presets;

    public AutoWorkPushOfferC2SPacket(boolean broadcast, String targetName,
                                      List<AutoWorkPresetData> presets) {
        this.broadcast = broadcast;
        this.targetName = targetName == null ? "" : targetName;
        this.presets = presets;
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

    public static void handle(AutoWorkPushOfferC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
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
        });
        ctx.get().setPacketHandled(true);
    }
}
