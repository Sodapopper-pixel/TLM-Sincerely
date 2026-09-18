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
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AutoWorkPushApplyS2CPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AutoWorkPushOfferC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AutoWorkSeedS2CPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AutoWorkSnapshotS2CPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.OpenAutoWorkConfigC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.RequestAutoWorkSnapshotC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.SetMaidAutoWorkC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.SetMaidAutoWorkPresetC2SPacket;

/**
 * Owns the single {@link SimpleChannel} used by the auto work switch.
 *
 * <p>Preset library edits are client-local and no longer travel over the
 * network; the wire format only carries maid bound snapshots, compat entries,
 * the login seed and preset pushes. The protocol version is bumped whenever
 * that format changes in a backward-incompatible way.
 */
public final class AutoWorkNetworking {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoWorkNetworking.class);
    private static final String PROTOCOL_VERSION = "3";

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

        registerS2C(channel, AutoWorkSnapshotS2CPacket.class,
                AutoWorkSnapshotS2CPacket.INDEX,
                AutoWorkSnapshotS2CPacket::encode,
                AutoWorkSnapshotS2CPacket::decode,
                AutoWorkSnapshotS2CPacket::handle);

        registerS2C(channel, AutoWorkSeedS2CPacket.class,
                AutoWorkSeedS2CPacket.INDEX,
                AutoWorkSeedS2CPacket::encode,
                AutoWorkSeedS2CPacket::decode,
                AutoWorkSeedS2CPacket::handle);

        registerS2C(channel, AutoWorkPushApplyS2CPacket.class,
                AutoWorkPushApplyS2CPacket.INDEX,
                AutoWorkPushApplyS2CPacket::encode,
                AutoWorkPushApplyS2CPacket::decode,
                AutoWorkPushApplyS2CPacket::handle);

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

        registerC2S(channel, OpenAutoWorkConfigC2SPacket.class,
                OpenAutoWorkConfigC2SPacket.INDEX,
                OpenAutoWorkConfigC2SPacket::encode,
                OpenAutoWorkConfigC2SPacket::decode,
                OpenAutoWorkConfigC2SPacket::handle);

        registerC2S(channel, AutoWorkPushOfferC2SPacket.class,
                AutoWorkPushOfferC2SPacket.INDEX,
                AutoWorkPushOfferC2SPacket::encode,
                AutoWorkPushOfferC2SPacket::decode,
                AutoWorkPushOfferC2SPacket::handle);

        LOGGER.info("[AutoWorkNetworking] registered channel {} protocol={}", CHANNEL_ID, PROTOCOL_VERSION);
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

    private static <MSG> void registerS2C(SimpleChannel channel,
                                          Class<MSG> type,
                                          int index,
                                          BiConsumer<MSG, FriendlyByteBuf> encoder,
                                          Function<FriendlyByteBuf, MSG> decoder,
                                          BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler) {
        MessageBuilder<MSG> builder = channel.messageBuilder(type, index, NetworkDirection.PLAY_TO_CLIENT);
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
