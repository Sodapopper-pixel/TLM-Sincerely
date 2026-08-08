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
 * S2C snapshot carrying the result of any auto work switch request.
 * The client cache is the single consumer; the GUI proxies read from it.
 */
public final class AutoWorkSnapshotS2CPacket {
    public static final int INDEX = 0;
    private final AutoWorkSnapshot snapshot;

    public AutoWorkSnapshotS2CPacket(AutoWorkSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public static void encode(AutoWorkSnapshotS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.snapshot.revision());
        buf.writeUUID(msg.snapshot.defaultPresetId());

        List<AutoWorkSnapshot.PresetEntry> presets = msg.snapshot.presets();
        buf.writeVarInt(presets.size());
        for (AutoWorkSnapshot.PresetEntry entry : presets) {
            buf.writeUUID(entry.id());
            buf.writeUtf(entry.name(), 64);
            List<ResourceLocation> order = entry.order();
            buf.writeVarInt(order.size());
            for (ResourceLocation task : order) {
                buf.writeResourceLocation(task);
            }
        }

        List<AutoWorkSnapshot.MaidEntry> maids = msg.snapshot.maids();
        buf.writeVarInt(maids.size());
        for (AutoWorkSnapshot.MaidEntry entry : maids) {
            buf.writeUUID(entry.maidId());
            buf.writeBoolean(entry.enabled());
            buf.writeUUID(entry.presetId());
            buf.writeVarInt(entry.stateRevision());
        }
    }

    public static AutoWorkSnapshotS2CPacket decode(FriendlyByteBuf buf) {
        int revision = buf.readVarInt();
        UUID defaultId = buf.readUUID();

        int presetCount = buf.readVarInt();
        List<AutoWorkSnapshot.PresetEntry> presets = new ArrayList<>(presetCount);
        for (int i = 0; i < presetCount; i++) {
            UUID id = buf.readUUID();
            String name = buf.readUtf(64);
            int orderSize = buf.readVarInt();
            List<ResourceLocation> order = new ArrayList<>(orderSize);
            for (int j = 0; j < orderSize; j++) {
                order.add(buf.readResourceLocation());
            }
            presets.add(new AutoWorkSnapshot.PresetEntry(id, name, order));
        }

        int maidCount = buf.readVarInt();
        List<AutoWorkSnapshot.MaidEntry> maids = new ArrayList<>(maidCount);
        for (int i = 0; i < maidCount; i++) {
            UUID maidId = buf.readUUID();
            boolean enabled = buf.readBoolean();
            UUID presetId = buf.readUUID();
            int stateRevision = buf.readVarInt();
            maids.add(new AutoWorkSnapshot.MaidEntry(maidId, enabled, presetId, stateRevision));
        }

        return new AutoWorkSnapshotS2CPacket(
                new AutoWorkSnapshot(revision, defaultId, presets, maids)
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
