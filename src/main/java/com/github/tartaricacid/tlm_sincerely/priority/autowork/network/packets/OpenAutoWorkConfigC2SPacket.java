package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.menu.AutoWorkConfigContainer;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkPermission;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S: request to open the standalone auto-work configuration GUI for
 * the maid identified by {@code maidId} (T-2 A5 / A6).
 *
 * <p>The server validates that the maid exists and that the sender is
 * the owner (or a level-2 operator, matching the rest of the auto work
 * permission model) and then asks Forge to open the
 * {@link AutoWorkConfigContainer} via
 * {@link NetworkHooks#openScreen}. The maid's entity id is written
 * into the buffer and consumed by the client-side IForgeMenuType
 * factory.
 */
public final class OpenAutoWorkConfigC2SPacket {
    public static final int INDEX = 10;
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAutoWorkConfigC2SPacket.class);

    private final UUID maidId;

    public OpenAutoWorkConfigC2SPacket(UUID maidId) {
        this.maidId = maidId;
    }

    public static void encode(OpenAutoWorkConfigC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.maidId);
    }

    public static OpenAutoWorkConfigC2SPacket decode(FriendlyByteBuf buf) {
        return new OpenAutoWorkConfigC2SPacket(buf.readUUID());
    }

    public static void handle(OpenAutoWorkConfigC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            NetworkEvent.Context c = ctx.get();
            var sender = c.getSender();
            if (sender == null) {
                return;
            }
            EntityMaid maid = AutoWorkPermission.resolveMaid(sender, msg.maidId);
            if (maid == null) {
                LOGGER.debug("[AutoWork] OpenAutoWorkConfig: unknown maid {}", msg.maidId);
                return;
            }
            if (!AutoWorkPermission.canControlMaid(sender, maid)) {
                LOGGER.info("[AutoWork] OpenAutoWorkConfig denied for {} on maid {}",
                        sender.getName().getString(), msg.maidId);
                return;
            }
            int entityId = maid.getId();
            NetworkHooks.openScreen(sender, AutoWorkConfigContainer.createProvider(entityId),
                    buf -> buf.writeInt(entityId));
        });
        ctx.get().setPacketHandled(true);
    }
}
