package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.client.network.ClientAutoWorkService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkSnapshot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * S2C snapshot carrying the server-authoritative maid state (bound snapshots
 * and compat entries). The client library is local and never sent here.
 */
public final class AutoWorkSnapshotS2CPacket {
    public static final int INDEX = 0;
    private static final int MAX_COMPAT_ENTRIES = 4096;
    private static final int MAX_MAIDS = 8192;
    private static final int MAX_ORDER = 512;
    private final AutoWorkSnapshot snapshot;

    public AutoWorkSnapshotS2CPacket(AutoWorkSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public static void encode(AutoWorkSnapshotS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.snapshot.revision());

        List<AutoWorkSnapshot.CompatEntry> compatEntries = msg.snapshot.compatEntries();
        buf.writeVarInt(compatEntries.size());
        for (AutoWorkSnapshot.CompatEntry entry : compatEntries) {
            buf.writeResourceLocation(entry.taskUid());
            buf.writeUtf(entry.level(), 32);
            buf.writeUtf(entry.reason(), 128);
        }

        List<AutoWorkSnapshot.MaidEntry> maids = msg.snapshot.maids();
        buf.writeVarInt(maids.size());
        for (AutoWorkSnapshot.MaidEntry entry : maids) {
            buf.writeUUID(entry.maidId());
            buf.writeBoolean(entry.enabled());
            buf.writeUUID(entry.presetId());
            buf.writeUtf(entry.presetName(), 64);
            List<ResourceLocation> order = entry.order();
            // Clamp to the decode cap so a legacy/oversized bound order can
            // never make the client reject the snapshot and drop the link.
            int orderSize = Math.min(order.size(), MAX_ORDER);
            buf.writeVarInt(orderSize);
            for (int i = 0; i < orderSize; i++) {
                buf.writeResourceLocation(order.get(i));
            }
            buf.writeBoolean(entry.snapshotBaked());
            buf.writeVarInt(entry.stateRevision());
        }
    }

    public static AutoWorkSnapshotS2CPacket decode(FriendlyByteBuf buf) {
        int revision = buf.readVarInt();

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
                new AutoWorkSnapshot(revision, compatEntries, maids)
        );
    }

    public static void handle(AutoWorkSnapshotS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        // S2C packets run on the network thread; the client cache is
        // safe to mutate from any thread because all consumers read it
        // from the main client thread. We dispatch via DistExecutor to
        // avoid a class-not-found if a server-only build accidentally
        // touches the client cache reference.
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientAutoWorkService.get().accept(msg.snapshot)));
        ctx.get().setPacketHandled(true);
    }

    public AutoWorkSnapshot snapshot() {
        return snapshot;
    }
}
