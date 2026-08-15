package com.github.tartaricacid.tlm_sincerely.priority.autowork.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.network.simple.SimpleChannel.MessageBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AddPresetTaskC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AutoWorkSnapshotS2CPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.CreatePresetC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.DeletePresetC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.MovePresetTaskC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.OpenAutoWorkConfigC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.RemovePresetTaskC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.RenamePresetC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.RequestAutoWorkSnapshotC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.SetMaidAutoWorkC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.SetMaidAutoWorkPresetC2SPacket;

/**
 * Owns the single {@link SimpleChannel} used by the auto work switch
 * (T-2 A3).
 *
 * <p>The channel is created exactly once at mod construction so packet
 * indices are stable for the lifetime of the JVM. The protocol version
 * is bumped if the wire format changes in a backward-incompatible way;
 * mismatches cause Forge to refuse the connection.
 */
public final class AutoWorkNetworking {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoWorkNetworking.class);
    private static final String PROTOCOL_VERSION = "2";

    private static final ResourceLocation CHANNEL_ID =
            new ResourceLocation(SincerelyExtension.MOD_ID, "auto_work");

    private static SimpleChannel channel;

    private AutoWorkNetworking() {
    }

    /**
     * Builds and registers the channel. Idempotent: subsequent calls are
     * no-ops.
     */
    public static synchronized void register() {
        if (channel != null) {
            return;
        }
        channel = NetworkRegistry.newSimpleChannel(
                CHANNEL_ID,
                () -> PROTOCOL_VERSION,
                PROTOCOL_VERSION::equals,
                PROTOCOL_VERSION::equals
        );

        // S2C: server → client snapshot.
        MessageBuilder<AutoWorkSnapshotS2CPacket> s2c = channel.messageBuilder(
                AutoWorkSnapshotS2CPacket.class, AutoWorkSnapshotS2CPacket.INDEX,
                NetworkDirection.PLAY_TO_CLIENT
        );
        s2c.encoder(AutoWorkSnapshotS2CPacket::encode);
        s2c.decoder(AutoWorkSnapshotS2CPacket::decode);
        s2c.consumerMainThread(AutoWorkSnapshotS2CPacket::handle);
        s2c.add();

        // C2S: request a snapshot.
        registerC2S(channel, RequestAutoWorkSnapshotC2SPacket.class,
                RequestAutoWorkSnapshotC2SPacket.INDEX,
                RequestAutoWorkSnapshotC2SPacket::encode,
                RequestAutoWorkSnapshotC2SPacket::decode,
                RequestAutoWorkSnapshotC2SPacket::handle);

        registerC2S(channel, SetMaidAutoWorkC2SPacket.class,
                SetMaidAutoWorkC2SPacket.INDEX,
                SetMaidAutoWorkC2SPacket::encode,
                SetMaidAutoWorkC2SPacket::decode,
                SetMaidAutoWorkC2SPacket::handle);

        registerC2S(channel, SetMaidAutoWorkPresetC2SPacket.class,
                SetMaidAutoWorkPresetC2SPacket.INDEX,
                SetMaidAutoWorkPresetC2SPacket::encode,
                SetMaidAutoWorkPresetC2SPacket::decode,
                SetMaidAutoWorkPresetC2SPacket::handle);

        registerC2S(channel, CreatePresetC2SPacket.class,
                CreatePresetC2SPacket.INDEX,
                CreatePresetC2SPacket::encode,
                CreatePresetC2SPacket::decode,
                CreatePresetC2SPacket::handle);

        registerC2S(channel, RenamePresetC2SPacket.class,
                RenamePresetC2SPacket.INDEX,
                RenamePresetC2SPacket::encode,
                RenamePresetC2SPacket::decode,
                RenamePresetC2SPacket::handle);

        registerC2S(channel, DeletePresetC2SPacket.class,
                DeletePresetC2SPacket.INDEX,
                DeletePresetC2SPacket::encode,
                DeletePresetC2SPacket::decode,
                DeletePresetC2SPacket::handle);

        registerC2S(channel, AddPresetTaskC2SPacket.class,
                AddPresetTaskC2SPacket.INDEX,
                AddPresetTaskC2SPacket::encode,
                AddPresetTaskC2SPacket::decode,
                AddPresetTaskC2SPacket::handle);

        registerC2S(channel, RemovePresetTaskC2SPacket.class,
                RemovePresetTaskC2SPacket.INDEX,
                RemovePresetTaskC2SPacket::encode,
                RemovePresetTaskC2SPacket::decode,
                RemovePresetTaskC2SPacket::handle);

        registerC2S(channel, MovePresetTaskC2SPacket.class,
                MovePresetTaskC2SPacket.INDEX,
                MovePresetTaskC2SPacket::encode,
                MovePresetTaskC2SPacket::decode,
                MovePresetTaskC2SPacket::handle);

        // C2S: open the standalone auto-work config container for a maid.
        registerC2S(channel, OpenAutoWorkConfigC2SPacket.class,
                OpenAutoWorkConfigC2SPacket.INDEX,
                OpenAutoWorkConfigC2SPacket::encode,
                OpenAutoWorkConfigC2SPacket::decode,
                OpenAutoWorkConfigC2SPacket::handle);

        LOGGER.info("[AutoWorkNetworking] registered channel {}", CHANNEL_ID);
    }

    private static <MSG> void registerC2S(SimpleChannel channel,
                                          Class<MSG> type,
                                          int index,
                                          BiConsumer<MSG, FriendlyByteBuf> encoder,
                                          Function<FriendlyByteBuf, MSG> decoder,
                                          BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler) {
        MessageBuilder<MSG> builder = channel.messageBuilder(type, index, NetworkDirection.PLAY_TO_SERVER);
        builder.encoder(encoder);
        builder.decoder(decoder);
        builder.consumerMainThread(handler);
        builder.add();
    }

    /** Returns the live channel. Throws if {@link #register()} was not called. */
    public static SimpleChannel channel() {
        SimpleChannel ch = channel;
        if (ch == null) {
            throw new IllegalStateException("AutoWorkNetworking.register() must be called during common setup");
        }
        return ch;
    }

    /** For tests; not part of the public API. */
    public static Optional<SimpleChannel> channelOptional() {
        return Optional.ofNullable(channel);
    }
}
