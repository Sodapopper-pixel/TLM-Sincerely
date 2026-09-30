package com.github.tartaricacid.tlm_sincerely.releasewire;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPreset;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Wire projection of a single preset used by the seed / apply / push packets.
 *
 * <p>Order entries are task UIDs; the server sanitizes them before relaying a
 * push, and clients ignore entries that are not registered tasks.
 */
public record AutoWorkPresetData(UUID id, String name, List<ResourceLocation> order) {
    public static final int MAX_PRESETS = 64;
    public static final int MAX_TASKS = 512;

    public AutoWorkPresetData {
        name = name == null ? "" : name;
        order = order == null ? List.of() : List.copyOf(order);
    }

    public static AutoWorkPresetData from(AutoWorkPreset preset) {
        return new AutoWorkPresetData(preset.getId(), preset.getName(), preset.getOrder());
    }

    public AutoWorkPreset toPreset() {
        return new AutoWorkPreset(id, name, order);
    }

    public static void encode(FriendlyByteBuf buf, AutoWorkPresetData data) {
        buf.writeUUID(data.id());
        buf.writeUtf(data.name(), 64);
        // Clamp to the decode caps: an oversized local library must never
        // produce a packet the receiver rejects, because a decoder exception
        // closes the connection.
        int taskCount = Math.min(data.order().size(), MAX_TASKS);
        buf.writeVarInt(taskCount);
        for (int i = 0; i < taskCount; i++) {
            buf.writeResourceLocation(data.order().get(i));
        }
    }

    public static AutoWorkPresetData decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String name = buf.readUtf(64);
        int taskCount = buf.readVarInt();
        if (taskCount < 0 || taskCount > MAX_TASKS) {
            throw new IllegalArgumentException("Invalid preset task count: " + taskCount);
        }
        List<ResourceLocation> order = new ArrayList<>(taskCount);
        for (int i = 0; i < taskCount; i++) {
            order.add(buf.readResourceLocation());
        }
        return new AutoWorkPresetData(id, name, order);
    }

    public static void encodeList(FriendlyByteBuf buf, List<AutoWorkPresetData> presets) {
        // Same cap as the decoder; see encode.
        int count = Math.min(presets.size(), MAX_PRESETS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            encode(buf, presets.get(i));
        }
    }

    public static List<AutoWorkPresetData> decodeList(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_PRESETS) {
            throw new IllegalArgumentException("Invalid preset count: " + count);
        }
        List<AutoWorkPresetData> presets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            presets.add(decode(buf));
        }
        return presets;
    }
}
