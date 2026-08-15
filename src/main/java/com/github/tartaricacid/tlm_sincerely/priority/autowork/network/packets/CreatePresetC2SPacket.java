package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPreset;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPresetService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkStateService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkPermission;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkServerHandler;
import com.github.tartaricacid.tlm_sincerely.priority.TaskAutoSwitchHandler;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;
import java.util.UUID;

/** C2S: create a new preset. OP-only. */
public final class CreatePresetC2SPacket {
    public static final int INDEX = 4;
    private static final Logger LOGGER = LoggerFactory.getLogger(CreatePresetC2SPacket.class);

    private final String name;
    private final UUID targetMaidId;

    public CreatePresetC2SPacket(String name) {
        this(name, null);
    }

    /**
     * Creates a preset and, when a controlled maid is supplied, atomically
     * makes it that maid's active preset.
     */
    public CreatePresetC2SPacket(String name, UUID targetMaidId) {
        this.name = name;
        this.targetMaidId = targetMaidId;
    }

    public static void encode(CreatePresetC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.name, 64);
        buf.writeBoolean(msg.targetMaidId != null);
        if (msg.targetMaidId != null) {
            buf.writeUUID(msg.targetMaidId);
        }
    }

    public static CreatePresetC2SPacket decode(FriendlyByteBuf buf) {
        String name = buf.readUtf(64);
        return new CreatePresetC2SPacket(name, buf.readBoolean() ? buf.readUUID() : null);
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
            EntityMaid targetMaid = null;
            AutoWorkStateService stateService = null;
            if (msg.targetMaidId != null) {
                targetMaid = AutoWorkPermission.resolveMaid(sender, msg.targetMaidId);
                if (targetMaid == null || !AutoWorkPermission.canControlMaid(sender, targetMaid)) {
                    LOGGER.info("[AutoWork] CreatePreset target-maid binding denied for {} on maid {}",
                            sender.getName().getString(), msg.targetMaidId);
                    return;
                }
                stateService = AutoWorkStateService.getOrNull(sender.server);
                if (stateService == null) {
                    return;
                }
            }
            AutoWorkPresetService presetService =
                    AutoWorkPresetService.getOrNull(sender.server);
            if (presetService == null) {
                return;
            }
            AutoWorkPreset preset = presetService.createPreset(name);
            if (targetMaid != null) {
                stateService.setPresetId(targetMaid, preset.getId());
                TaskAutoSwitchHandler.requestImmediateEvaluation(targetMaid, "CREATE_PRESET", true);
            }
            LOGGER.debug("[AutoWork] CreatePreset name={} id={} player={} targetMaid={}", name, preset.getId(),
                    sender.getName().getString(), msg.targetMaidId);
            AutoWorkServerHandler.sendSnapshot(sender);
        });
        ctx.get().setPacketHandled(true);
    }
}
