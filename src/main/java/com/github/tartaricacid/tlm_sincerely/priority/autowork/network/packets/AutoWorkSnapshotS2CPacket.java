package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import com.github.tartaricacid.tlm_sincerely.client.network.AutoWorkClientPayloadHandlers;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkSnapshot;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S2C snapshot carrying the server-authoritative maid state (bound snapshots
 * and compat entries) plus the server-authoritative global auto work switch
 * (COMMON config is not synced to clients). The client library is local and
 * never sent here.
 */
public record AutoWorkSnapshotS2CPacket(AutoWorkSnapshot snapshot) implements CustomPacketPayload {
    public static final Type<AutoWorkSnapshotS2CPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SincerelyExtension.MOD_ID, "auto_work/snapshot"));

    private static final int MAX_COMPAT_ENTRIES = 4096;
    private static final int MAX_MAIDS = 8192;
    private static final int MAX_ORDER = 512;

    public static final StreamCodec<ByteBuf, AutoWorkSnapshotS2CPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AutoWorkSnapshotS2CPacket decode(ByteBuf buf) {
            return AutoWorkSnapshotS2CPacket.decode(new FriendlyByteBuf(buf));
        }

        @Override
        public void encode(ByteBuf buf, AutoWorkSnapshotS2CPacket msg) {
            AutoWorkSnapshotS2CPacket.encode(msg, new FriendlyByteBuf(buf));
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(AutoWorkSnapshotS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.snapshot.revision());
        // Server-authoritative global switch; see AutoWorkSnapshot.
        buf.writeBoolean(msg.snapshot.globalEnabled());

        List<AutoWorkSnapshot.CompatEntry> compatEntries = msg.snapshot.compatEntries();
        // Clamp both list sizes to the decode caps as well: a decoder
        // exception closes the connection, so the encoder must never emit a
        // count the receiver would reject (same contract as order below and
        // AutoWorkPresetData.encode).
        int compatSize = Math.min(compatEntries.size(), MAX_COMPAT_ENTRIES);
        buf.writeVarInt(compatSize);
        for (int i = 0; i < compatSize; i++) {
            AutoWorkSnapshot.CompatEntry entry = compatEntries.get(i);
            buf.writeResourceLocation(entry.taskUid());
            buf.writeUtf(entry.level(), 32);
            buf.writeUtf(entry.reason(), 128);
        }

        List<AutoWorkSnapshot.MaidEntry> maids = msg.snapshot.maids();
        int maidSize = Math.min(maids.size(), MAX_MAIDS);
        buf.writeVarInt(maidSize);
        for (int i = 0; i < maidSize; i++) {
            AutoWorkSnapshot.MaidEntry entry = maids.get(i);
            buf.writeUUID(entry.maidId());
            buf.writeBoolean(entry.enabled());
            buf.writeUUID(entry.presetId());
            buf.writeUtf(entry.presetName(), 64);
            List<ResourceLocation> order = entry.order();
            // Clamp to the decode cap so a legacy/oversized bound order can
            // never make the client reject the snapshot and drop the link.
            int orderSize = Math.min(order.size(), MAX_ORDER);
            buf.writeVarInt(orderSize);
            for (int j = 0; j < orderSize; j++) {
                buf.writeResourceLocation(order.get(j));
            }
            buf.writeBoolean(entry.snapshotBaked());
            buf.writeVarInt(entry.stateRevision());
        }
    }

    public static AutoWorkSnapshotS2CPacket decode(FriendlyByteBuf buf) {
        int revision = buf.readVarInt();
        boolean globalEnabled = buf.readBoolean();

        int compatCount = buf.readVarInt();
        if (compatCount < 0 || compatCount > MAX_COMPAT_ENTRIES) {
            throw new IllegalArgumentException("Invalid compat entry count: " + compatCount);
        }
        List<AutoWorkSnapshot.CompatEntry> compatEntries = new ArrayList<>(compatCount);
        for (int i = 0; i < compatCount; i++) {
            compatEntries.add(new AutoWorkSnapshot.CompatEntry(
                    buf.readResourceLocation(), buf.readUtf(32), buf.readUtf(128)));
        }

        int maidCount = buf.readVarInt();
        if (maidCount < 0 || maidCount > MAX_MAIDS) {
            throw new IllegalArgumentException("Invalid maid count: " + maidCount);
        }
        List<AutoWorkSnapshot.MaidEntry> maids = new ArrayList<>(maidCount);
        for (int i = 0; i < maidCount; i++) {
            UUID maidId = buf.readUUID();
            boolean enabled = buf.readBoolean();
            UUID presetId = buf.readUUID();
            String presetName = buf.readUtf(64);
            int orderSize = buf.readVarInt();
            if (orderSize < 0 || orderSize > MAX_ORDER) {
                throw new IllegalArgumentException("Invalid bound order size: " + orderSize);
            }
            List<ResourceLocation> order = new ArrayList<>(orderSize);
            for (int j = 0; j < orderSize; j++) {
                order.add(buf.readResourceLocation());
            }
            boolean snapshotBaked = buf.readBoolean();
            int stateRevision = buf.readVarInt();
            maids.add(new AutoWorkSnapshot.MaidEntry(maidId, enabled, presetId, presetName,
                    order, snapshotBaked, stateRevision));
        }

        return new AutoWorkSnapshotS2CPacket(
                new AutoWorkSnapshot(revision, globalEnabled, compatEntries, maids)
        );
    }

    public static void handle(AutoWorkSnapshotS2CPacket msg, IPayloadContext context) {
        // S2C packets run on the client main thread (registrar default); the
        // client cache is safe to mutate there because all consumers read it
        // from the main client thread. The flow guard keeps a stray server
        // call from resolving the client-only handler class; on a dedicated
        // server this method is never invoked, so the reference stays lazy.
        if (context.flow().isClientbound()) {
            AutoWorkClientPayloadHandlers.handleSnapshot(msg);
        }
    }
}
