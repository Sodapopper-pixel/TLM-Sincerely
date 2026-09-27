package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.menu.AutoWorkConfigContainer;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkPermission;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * C2S: request to open the standalone auto-work configuration GUI for
 * the maid identified by {@code maidId} (T-2 A5 / A6).
 *
 * <p>The server validates that the maid exists and that the sender is
 * the owner (or a level-2 operator, matching the rest of the auto work
 * permission model) and then asks the NeoForge menu API to open the
 * {@link AutoWorkConfigContainer} via
 * {@link ServerPlayer#openMenu}. The maid's entity id is written
 * into the buffer and consumed by the client-side IForgeMenuType
 * factory.
 */
public record OpenAutoWorkConfigC2SPacket(UUID maidId) implements CustomPacketPayload {
    public static final Type<OpenAutoWorkConfigC2SPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SincerelyExtension.MOD_ID, "auto_work/open_config"));

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAutoWorkConfigC2SPacket.class);

    // Mirrors the removed ByteBufCodecs.UUID_STREAM_CODEC, which only exists
    // on newer Minecraft versions; 1.21.1 ships the static FriendlyByteBuf
    // helpers instead.
    private static final StreamCodec<ByteBuf, UUID> UUID_STREAM_CODEC =
            StreamCodec.of(FriendlyByteBuf::writeUUID, FriendlyByteBuf::readUUID);

    public static final StreamCodec<ByteBuf, OpenAutoWorkConfigC2SPacket> STREAM_CODEC = StreamCodec.composite(
            UUID_STREAM_CODEC, OpenAutoWorkConfigC2SPacket::maidId,
            OpenAutoWorkConfigC2SPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenAutoWorkConfigC2SPacket msg, IPayloadContext context) {
        var sender = context.player() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
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
        sender.openMenu(AutoWorkConfigContainer.createProvider(entityId),
                buf -> buf.writeInt(entityId));
    }
}
