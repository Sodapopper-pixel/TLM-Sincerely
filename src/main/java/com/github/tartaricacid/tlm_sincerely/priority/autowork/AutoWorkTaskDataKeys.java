package com.github.tartaricacid.tlm_sincerely.priority.autowork;

import com.github.tartaricacid.touhoulittlemaid.api.entity.data.TaskDataKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Static holder for the {@link TaskDataKey} used to persist the maid bound
 * snapshot via TLM's {@code TaskDataRegister}.
 *
 * <p>NBT layout: {@code enabled} (bool), {@code presetId} (UUID),
 * {@code presetName} (string), {@code order} (list of task UID strings),
 * {@code bound} (bool, true once an order snapshot has been baked) and
 * {@code revision} (int).
 */
public final class AutoWorkTaskDataKeys {
    public static final String MOD_ID = "tlm_sincerely";
    public static final ResourceLocation STATE_KEY_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "auto_work_state");

    private static final String TAG_ENABLED = "enabled";
    private static final String TAG_PRESET_ID = "presetId";
    private static final String TAG_PRESET_NAME = "presetName";
    private static final String TAG_ORDER = "order";
    private static final String TAG_BOUND = "bound";
    private static final String TAG_REVISION = "revision";

    public static final TaskDataKey<AutoWorkState> STATE_KEY = new TaskDataKey<>() {
        @Override
        public ResourceLocation getKey() {
            return STATE_KEY_ID;
        }

        @Override
        public CompoundTag writeSaveData(AutoWorkState data) {
            return writeTag(data);
        }

        @Override
        public AutoWorkState readSaveData(CompoundTag tag) {
            return readTag(tag);
        }
    };

    private AutoWorkTaskDataKeys() {
    }

    private static CompoundTag writeTag(AutoWorkState data) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(TAG_ENABLED, data.enabled());
        tag.putUUID(TAG_PRESET_ID, data.presetId());
        tag.putString(TAG_PRESET_NAME, data.presetName());
        ListTag order = new ListTag();
        for (ResourceLocation taskId : data.order()) {
            order.add(StringTag.valueOf(taskId.toString()));
        }
        tag.put(TAG_ORDER, order);
        tag.putBoolean(TAG_BOUND, data.snapshotBaked());
        tag.putInt(TAG_REVISION, data.revision());
        return tag;
    }

    private static AutoWorkState readTag(CompoundTag tag) {
        boolean enabled = tag.contains(TAG_ENABLED) && tag.getBoolean(TAG_ENABLED);
        UUID presetId = tag.hasUUID(TAG_PRESET_ID) ? tag.getUUID(TAG_PRESET_ID) : AutoWorkState.NO_PRESET_ID;
        String presetName = tag.contains(TAG_PRESET_NAME, Tag.TAG_STRING)
                ? tag.getString(TAG_PRESET_NAME) : "";
        List<ResourceLocation> order = new ArrayList<>();
        if (tag.contains(TAG_ORDER, Tag.TAG_LIST)) {
            ListTag list = tag.getList(TAG_ORDER, Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                try {
                    ResourceLocation taskId = ResourceLocation.parse(list.getString(i));
                    if (!order.contains(taskId)) {
                        order.add(taskId);
                    }
                } catch (RuntimeException ignored) {
                    // skip malformed entries; the rest of the snapshot stays usable
                }
            }
        }
        boolean bound = tag.contains(TAG_BOUND) && tag.getBoolean(TAG_BOUND);
        int revision = tag.contains(TAG_REVISION) ? tag.getInt(TAG_REVISION) : 0;
        return new AutoWorkState(enabled, presetId, presetName, order, bound, revision);
    }
}
