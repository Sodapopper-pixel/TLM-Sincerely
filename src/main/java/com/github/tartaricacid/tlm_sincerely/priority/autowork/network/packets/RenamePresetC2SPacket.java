package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPresetService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkPermission;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkServerHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.function.Supplier;

/** C2S: rename an existing preset. OP-only. */
public final class RenamePresetC2SPacket {
    public static final int INDEX = 5;
    private static final Logger LOGGER = LoggerFactory.getLogger(RenamePresetC2SPacket.class);

    private final UUID presetId;
    private final String newName;

    public RenamePresetC2SPacket(UUID presetId, String newName) {
        this.presetId = presetId;
        this.newName = newName;
    }

    public static void encode(RenamePresetC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.presetId);
        buf.writeUtf(msg.newName, 64);
    }

    public static RenamePresetC2SPacket decode(FriendlyByteBuf buf) {
        return new RenamePresetC2SPacket(buf.readUUID(), buf.readUtf(64));
    }

    public static void handle(RenamePresetC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            NetworkEvent.Context c = ctx.get();
            var sender = c.getSender();
            if (sender == null || msg.presetId == null) {
                return;
            }
            if (!AutoWorkPermission.canEditLibrary(sender)) {
                LOGGER.info("[AutoWork] RenamePreset denied for {}", sender.getName().getString());
                return;
            }
            String name = msg.newName == null ? "" : msg.newName.trim();
            if (name.isEmpty()) {
                return;
            }
            AutoWorkPresetService presetService =
                    AutoWorkPresetService.getOrNull(sender.server);
            if (presetService == null) {
                return;
            }
            boolean ok = presetService.renamePreset(msg.presetId, name);
            if (!ok) {
                LOGGER.info("[AutoWork] RenamePreset failed for {} by {}",
                        msg.presetId, sender.getName().getString());
                return;
            }
            AutoWorkServerHandler.sendSnapshot(sender);
        });
        ctx.get().setPacketHandled(true);
    }
}
