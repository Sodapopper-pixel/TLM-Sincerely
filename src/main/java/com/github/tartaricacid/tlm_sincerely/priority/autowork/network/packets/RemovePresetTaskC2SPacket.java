package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPresetService;
import com.github.tartaricacid.tlm_sincerely.priority.TaskAutoSwitchHandler;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkPermission;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkServerHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.function.Supplier;

/** C2S: remove a task from a preset's order list. OP-only. */
public final class RemovePresetTaskC2SPacket {
    public static final int INDEX = 8;
    private static final Logger LOGGER = LoggerFactory.getLogger(RemovePresetTaskC2SPacket.class);

    private final UUID presetId;
    private final ResourceLocation taskId;

    public RemovePresetTaskC2SPacket(UUID presetId, ResourceLocation taskId) {
        this.presetId = presetId;
        this.taskId = taskId;
    }

    public static void encode(RemovePresetTaskC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.presetId);
        buf.writeResourceLocation(msg.taskId);
    }

    public static RemovePresetTaskC2SPacket decode(FriendlyByteBuf buf) {
        return new RemovePresetTaskC2SPacket(buf.readUUID(), buf.readResourceLocation());
    }

    public static void handle(RemovePresetTaskC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            NetworkEvent.Context c = ctx.get();
            var sender = c.getSender();
            if (sender == null || msg.presetId == null || msg.taskId == null) {
                return;
            }
            if (!AutoWorkPermission.canEditLibrary(sender)) {
                LOGGER.info("[AutoWork] RemovePresetTask denied for {}", sender.getName().getString());
                return;
            }
            AutoWorkPresetService presetService =
                    AutoWorkPresetService.getOrNull(sender.server);
            if (presetService == null) {
                return;
            }
            boolean removed = presetService.removeTask(msg.presetId, msg.taskId);
            if (!removed) {
                LOGGER.info("[AutoWork] RemovePresetTask failed for preset {} task {}",
                        msg.presetId, msg.taskId);
                return;
            }
            TaskAutoSwitchHandler.requestPresetRescan(sender.server, msg.presetId, "REMOVE_PRESET_TASK");
            AutoWorkServerHandler.sendSnapshot(sender);
        });
        ctx.get().setPacketHandled(true);
    }
}
