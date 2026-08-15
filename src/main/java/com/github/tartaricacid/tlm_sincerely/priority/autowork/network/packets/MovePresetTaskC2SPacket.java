package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPreset;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPresetService;
import com.github.tartaricacid.tlm_sincerely.priority.TaskAutoSwitchHandler;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkPermission;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkServerHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** C2S: move a task within a preset's order list. OP-only. */
public final class MovePresetTaskC2SPacket {
    public static final int INDEX = 9;
    private static final Logger LOGGER = LoggerFactory.getLogger(MovePresetTaskC2SPacket.class);

    private final UUID presetId;
    private final ResourceLocation taskId;
    private final int targetIndex;

    public MovePresetTaskC2SPacket(UUID presetId, ResourceLocation taskId, int targetIndex) {
        this.presetId = presetId;
        this.taskId = taskId;
        this.targetIndex = targetIndex;
    }

    public static void encode(MovePresetTaskC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.presetId);
        buf.writeResourceLocation(msg.taskId);
        buf.writeVarInt(msg.targetIndex);
    }

    public static MovePresetTaskC2SPacket decode(FriendlyByteBuf buf) {
        return new MovePresetTaskC2SPacket(buf.readUUID(), buf.readResourceLocation(), buf.readVarInt());
    }

    public static void handle(MovePresetTaskC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            NetworkEvent.Context c = ctx.get();
            var sender = c.getSender();
            if (sender == null || msg.presetId == null || msg.taskId == null) {
                return;
            }
            if (!AutoWorkPermission.canEditLibrary(sender)) {
                LOGGER.info("[AutoWork] MovePresetTask denied for {}", sender.getName().getString());
                return;
            }
            AutoWorkPresetService presetService =
                    AutoWorkPresetService.getOrNull(sender.server);
            if (presetService == null) {
                return;
            }
            AutoWorkPreset preset = presetService.getPreset(msg.presetId);
            if (preset == null) {
                return;
            }
            List<ResourceLocation> order = preset.getOrder();
            if (!order.contains(msg.taskId)) {
                // Move requires the task to already be present; otherwise
                // the request is malformed.
                return;
            }
            int clamped = Math.max(0, Math.min(msg.targetIndex, order.size() - 1));
            boolean moved = presetService.moveTask(msg.presetId, msg.taskId, clamped);
            if (!moved) {
                LOGGER.info("[AutoWork] MovePresetTask no-op for preset {} task {} index {}",
                        msg.presetId, msg.taskId, clamped);
                return;
            }
            TaskAutoSwitchHandler.requestPresetRescan(sender.server, msg.presetId, "MOVE_PRESET_TASK");
            AutoWorkServerHandler.sendSnapshot(sender);
        });
        ctx.get().setPacketHandled(true);
    }
}
