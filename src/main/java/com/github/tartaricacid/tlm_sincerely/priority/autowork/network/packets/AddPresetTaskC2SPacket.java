package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPresetService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkPermission;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkServerHandler;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.function.Supplier;

/** C2S: append a task to a preset's order list. OP-only. */
public final class AddPresetTaskC2SPacket {
    public static final int INDEX = 7;
    private static final Logger LOGGER = LoggerFactory.getLogger(AddPresetTaskC2SPacket.class);

    private final UUID presetId;
    private final ResourceLocation taskId;

    public AddPresetTaskC2SPacket(UUID presetId, ResourceLocation taskId) {
        this.presetId = presetId;
        this.taskId = taskId;
    }

    public static void encode(AddPresetTaskC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.presetId);
        buf.writeResourceLocation(msg.taskId);
    }

    public static AddPresetTaskC2SPacket decode(FriendlyByteBuf buf) {
        return new AddPresetTaskC2SPacket(buf.readUUID(), buf.readResourceLocation());
    }

    public static void handle(AddPresetTaskC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            NetworkEvent.Context c = ctx.get();
            var sender = c.getSender();
            if (sender == null || msg.presetId == null || msg.taskId == null) {
                return;
            }
            if (!AutoWorkPermission.canEditLibrary(sender)) {
                LOGGER.info("[AutoWork] AddPresetTask denied for {}", sender.getName().getString());
                return;
            }
            if (TaskManager.findTask(msg.taskId).isEmpty()) {
                LOGGER.info("[AutoWork] AddPresetTask: unknown task {}", msg.taskId);
                return;
            }
            AutoWorkPresetService presetService =
                    AutoWorkPresetService.getOrNull(sender.server);
            if (presetService == null) {
                return;
            }
            boolean added = presetService.addTask(msg.presetId, msg.taskId);
            if (!added) {
                LOGGER.info("[AutoWork] AddPresetTask failed for preset {} task {}",
                        msg.presetId, msg.taskId);
                return;
            }
            AutoWorkServerHandler.sendSnapshot(sender);
        });
        ctx.get().setPacketHandled(true);
    }
}
