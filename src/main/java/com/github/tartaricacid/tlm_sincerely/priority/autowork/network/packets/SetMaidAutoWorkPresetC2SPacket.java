package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.priority.TaskAutoSwitchHandler;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkStateService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkPermission;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkServerHandler;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S: bind a full preset snapshot (id/name/order) to a single maid.
 *
 * <p>The order comes from the sender's private client library, so the server
 * validates it defensively. Selecting the same UUID again re-bakes the maid
 * with the client library's current copy.
 */
public final class SetMaidAutoWorkPresetC2SPacket {
    public static final int INDEX = 3;
    private static final Logger LOGGER = LoggerFactory.getLogger(SetMaidAutoWorkPresetC2SPacket.class);

    private final UUID maidId;
    private final UUID presetId;
    private final String presetName;
    private final List<ResourceLocation> order;

    public SetMaidAutoWorkPresetC2SPacket(UUID maidId, UUID presetId, String presetName,
                                          List<ResourceLocation> order) {
        this.maidId = maidId;
        this.presetId = presetId;
        this.presetName = presetName;
        this.order = order;
    }

    public static void encode(SetMaidAutoWorkPresetC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.maidId);
        buf.writeUUID(msg.presetId);
        buf.writeUtf(msg.presetName == null ? "" : msg.presetName, 64);
        List<ResourceLocation> order = msg.order == null ? List.of() : msg.order;
        buf.writeVarInt(order.size());
        for (ResourceLocation task : order) {
            buf.writeResourceLocation(task);
        }
    }

    public static SetMaidAutoWorkPresetC2SPacket decode(FriendlyByteBuf buf) {
        UUID maidId = buf.readUUID();
        UUID presetId = buf.readUUID();
        String name = buf.readUtf(64);
        int count = buf.readVarInt();
        if (count < 0 || count > 512) {
            throw new IllegalArgumentException("Invalid bound snapshot size: " + count);
        }
        List<ResourceLocation> order = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            order.add(buf.readResourceLocation());
        }
        return new SetMaidAutoWorkPresetC2SPacket(maidId, presetId, name, order);
    }

    public static void handle(SetMaidAutoWorkPresetC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var sender = ctx.get().getSender();
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
            AutoWorkStateService stateService = AutoWorkStateService.getOrNull(sender.server);
            if (stateService == null) {
                return;
            }
            stateService.bindSnapshot(maid, msg.presetId, msg.presetName, msg.order);
            TaskAutoSwitchHandler.requestImmediateEvaluation(maid, "PLAYER_SELECT_PRESET", true);
            LOGGER.debug("[AutoWork] SetMaidAutoWorkPreset maid={} preset={} tasks={}",
                    maid.getUUID(), msg.presetId, msg.order == null ? 0 : msg.order.size());
            AutoWorkServerHandler.sendSnapshot(sender);
        });
        ctx.get().setPacketHandled(true);
    }
}
