package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.client.autowork.AutoWorkClientLibrary;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPreset;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkPresetData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * S2C: the server's frozen seed library, sent once on login. The client only
 * imports it when it has no local library file yet, so the seed never
 * overwrites a private library.
 */
public final class AutoWorkSeedS2CPacket {
    public static final int INDEX = 11;

    private final UUID defaultPresetId;
    private final List<AutoWorkPresetData> presets;

    public AutoWorkSeedS2CPacket(UUID defaultPresetId, List<AutoWorkPresetData> presets) {
        this.defaultPresetId = defaultPresetId;
        this.presets = presets;
    }

    public static void encode(AutoWorkSeedS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.defaultPresetId);
        AutoWorkPresetData.encodeList(buf, msg.presets);
    }

    public static AutoWorkSeedS2CPacket decode(FriendlyByteBuf buf) {
        UUID defaultId = buf.readUUID();
        return new AutoWorkSeedS2CPacket(defaultId, AutoWorkPresetData.decodeList(buf));
    }

    public static void handle(AutoWorkSeedS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            List<AutoWorkPreset> presets = new ArrayList<>(msg.presets.size());
            for (AutoWorkPresetData data : msg.presets) {
                presets.add(data.toPreset());
            }
            AutoWorkClientLibrary.get().acceptSeed(msg.defaultPresetId, presets);
        }));
        ctx.get().setPacketHandled(true);
    }

    public UUID defaultPresetId() {
        return defaultPresetId;
    }

    public List<AutoWorkPresetData> presets() {
        return presets;
    }
}
