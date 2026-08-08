package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPreset;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPresetService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkPermission;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkServerHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/** C2S: create a new preset. OP-only. */
public final class CreatePresetC2SPacket {
    public static final int INDEX = 4;
    private static final Logger LOGGER = LoggerFactory.getLogger(CreatePresetC2SPacket.class);

    private final String name;

    public CreatePresetC2SPacket(String name) {
        this.name = name;
    }

    public static void encode(CreatePresetC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.name, 64);
    }

    public static CreatePresetC2SPacket decode(FriendlyByteBuf buf) {
        return new CreatePresetC2SPacket(buf.readUtf(64));
    }

    public static void handle(CreatePresetC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            NetworkEvent.Context c = ctx.get();
            var sender = c.getSender();
            if (sender == null) {
                return;
            }
            if (!AutoWorkPermission.canEditLibrary(sender)) {
                LOGGER.info("[AutoWork] CreatePreset denied for {}", sender.getName().getString());
                return;
            }
            String name = msg.name == null ? "" : msg.name.trim();
            if (name.isEmpty()) {
                return;
            }
            AutoWorkPresetService presetService =
                    AutoWorkPresetService.getOrNull(sender.server);
            if (presetService == null) {
                return;
            }
            AutoWorkPreset preset = presetService.createPreset(name);
            LOGGER.info("[AutoWork] CreatePreset {} -> {} by {}", name, preset.getId(),
                    sender.getName().getString());
            AutoWorkServerHandler.sendSnapshot(sender);
        });
        ctx.get().setPacketHandled(true);
    }
}
