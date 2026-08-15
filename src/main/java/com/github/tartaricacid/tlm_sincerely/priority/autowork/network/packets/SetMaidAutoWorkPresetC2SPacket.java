package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPresetService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkStateService;
import com.github.tartaricacid.tlm_sincerely.priority.TaskAutoSwitchHandler;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkPermission;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkServerHandler;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.function.Supplier;

/** C2S: change the active preset id for a single maid. */
public final class SetMaidAutoWorkPresetC2SPacket {
    public static final int INDEX = 3;
    private static final Logger LOGGER = LoggerFactory.getLogger(SetMaidAutoWorkPresetC2SPacket.class);

    private final UUID maidId;
    private final UUID presetId;

    public SetMaidAutoWorkPresetC2SPacket(UUID maidId, UUID presetId) {
        this.maidId = maidId;
        this.presetId = presetId;
    }

    public static void encode(SetMaidAutoWorkPresetC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.maidId);
        buf.writeUUID(msg.presetId);
    }

    public static SetMaidAutoWorkPresetC2SPacket decode(FriendlyByteBuf buf) {
        return new SetMaidAutoWorkPresetC2SPacket(buf.readUUID(), buf.readUUID());
    }

    public static void handle(SetMaidAutoWorkPresetC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            NetworkEvent.Context c = ctx.get();
            var sender = c.getSender();
            if (sender == null || msg.maidId == null || msg.presetId == null) {
                return;
            }
            EntityMaid maid = AutoWorkPermission.resolveMaid(sender, msg.maidId);
            if (maid == null) {
                LOGGER.debug("[AutoWork] SetMaidAutoWorkPreset: unknown maid {}", msg.maidId);
                return;
            }
            if (!AutoWorkPermission.canControlMaid(sender, maid)) {
                LOGGER.info("[AutoWork] SetMaidAutoWorkPreset denied for {} on maid {}",
                        sender.getName().getString(), msg.maidId);
                return;
            }
            AutoWorkPresetService presetService =
                    AutoWorkPresetService.getOrNull(sender.server);
            if (presetService == null || presetService.getPreset(msg.presetId) == null) {
                LOGGER.info("[AutoWork] SetMaidAutoWorkPreset: preset {} not found", msg.presetId);
                return;
            }
            AutoWorkStateService stateService =
                    AutoWorkStateService.getOrNull(sender.server);
            if (stateService == null) {
                return;
            }
            stateService.setPresetId(maid, msg.presetId);
            TaskAutoSwitchHandler.requestImmediateEvaluation(maid, "PLAYER_SELECT_PRESET", true);
            LOGGER.debug("[AutoWork] SetMaidAutoWorkPreset maid={} preset={}", maid.getUUID(), msg.presetId);
            AutoWorkServerHandler.sendSnapshot(sender);
        });
        ctx.get().setPacketHandled(true);
    }
}
