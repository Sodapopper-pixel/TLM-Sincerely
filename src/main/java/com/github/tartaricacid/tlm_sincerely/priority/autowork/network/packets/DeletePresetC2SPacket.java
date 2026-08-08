package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPresetService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkMaidResolver;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkPermission;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkServerHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S: delete a preset. OP-only. Reassigns every loaded maid that
 * referenced the preset to the current default before removing it.
 */
public final class DeletePresetC2SPacket {
    public static final int INDEX = 6;
    private static final Logger LOGGER = LoggerFactory.getLogger(DeletePresetC2SPacket.class);

    private final UUID presetId;

    public DeletePresetC2SPacket(UUID presetId) {
        this.presetId = presetId;
    }

    public static void encode(DeletePresetC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.presetId);
    }

    public static DeletePresetC2SPacket decode(FriendlyByteBuf buf) {
        return new DeletePresetC2SPacket(buf.readUUID());
    }

    public static void handle(DeletePresetC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            NetworkEvent.Context c = ctx.get();
            var sender = c.getSender();
            if (sender == null || msg.presetId == null) {
                return;
            }
            if (!AutoWorkPermission.canEditLibrary(sender)) {
                LOGGER.info("[AutoWork] DeletePreset denied for {}", sender.getName().getString());
                return;
            }
            AutoWorkPresetService presetService =
                    AutoWorkPresetService.getOrNull(sender.server);
            if (presetService == null) {
                return;
            }
            if (AutoWorkMaidResolver.isLastPreset(sender.server, msg.presetId)) {
                LOGGER.info("[AutoWork] DeletePreset refused: {} is the only preset", msg.presetId);
                return;
            }
            int reassigned = AutoWorkMaidResolver.reassignLoadedMaidsToDefault(
                    sender.server, msg.presetId);
            boolean ok = presetService.deletePreset(msg.presetId);
            if (!ok) {
                LOGGER.info("[AutoWork] DeletePreset failed for {} by {}",
                        msg.presetId, sender.getName().getString());
                return;
            }
            if (reassigned > 0) {
                LOGGER.info("[AutoWork] DeletePreset: reassigned {} maids from {} to default",
                        reassigned, msg.presetId);
            }
            AutoWorkServerHandler.sendSnapshot(sender);
        });
        ctx.get().setPacketHandled(true);
    }
}
