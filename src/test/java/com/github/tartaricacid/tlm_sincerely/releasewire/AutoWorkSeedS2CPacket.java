package com.github.tartaricacid.tlm_sincerely.releasewire;



import com.github.tartaricacid.tlm_sincerely.releasewire.AutoWorkPresetData;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;
import java.util.UUID;

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

    /** Round-trip 测试只覆盖 encode/decode；handle 依赖客户端类，不在测试范围。 */
    public static void handle(AutoWorkSeedS2CPacket msg, Object ctx) {
        throw new UnsupportedOperationException();
    }

    public UUID defaultPresetId() {
        return defaultPresetId;
    }

    public List<AutoWorkPresetData> presets() {
        return presets;
    }
}
