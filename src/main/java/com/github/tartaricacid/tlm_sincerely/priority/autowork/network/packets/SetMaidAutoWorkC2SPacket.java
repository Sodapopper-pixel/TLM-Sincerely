package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

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

/** C2S: enable or disable the auto work switch for a single maid. */
public final class SetMaidAutoWorkC2SPacket {
    public static final int INDEX = 2;
    private static final Logger LOGGER = LoggerFactory.getLogger(SetMaidAutoWorkC2SPacket.class);

    private final UUID maidId;
    private final boolean enabled;

    public SetMaidAutoWorkC2SPacket(UUID maidId, boolean enabled) {
        this.maidId = maidId;
        this.enabled = enabled;
    }

    public static void encode(SetMaidAutoWorkC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.maidId);
        buf.writeBoolean(msg.enabled);
    }

    public static SetMaidAutoWorkC2SPacket decode(FriendlyByteBuf buf) {
        return new SetMaidAutoWorkC2SPacket(buf.readUUID(), buf.readBoolean());
    }

    public static void handle(SetMaidAutoWorkC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            NetworkEvent.Context c = ctx.get();
            var sender = c.getSender();
            if (sender == null) {
                return;
            }
            EntityMaid maid = AutoWorkPermission.resolveMaid(sender, msg.maidId);
            if (maid == null) {
                LOGGER.debug("[AutoWork] SetMaidAutoWork: unknown maid {}", msg.maidId);
                return;
            }
            if (!AutoWorkPermission.canControlMaid(sender, maid)) {
                LOGGER.info("[AutoWork] SetMaidAutoWork denied for {} on maid {}",
                        sender.getName().getString(), msg.maidId);
                return;
            }
            AutoWorkStateService stateService =
                    AutoWorkStateService.getOrNull(sender.server);
            if (stateService == null) {
                return;
            }
            stateService.setEnabled(maid, msg.enabled);
            if (msg.enabled) {
                TaskAutoSwitchHandler.requestImmediateEvaluation(maid, "PLAYER_ENABLE");
            }
            LOGGER.debug("[AutoWork] SetMaidAutoWork maid={} enabled={}", maid.getUUID(), msg.enabled);
            AutoWorkServerHandler.sendSnapshot(sender);
        });
        ctx.get().setPacketHandled(true);
    }
}
