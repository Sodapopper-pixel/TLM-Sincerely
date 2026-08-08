package com.github.tartaricacid.tlm_sincerely.priority.autowork;

import com.github.tartaricacid.touhoulittlemaid.api.entity.data.TaskDataKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Static holder for the {@link TaskDataKey} used to persist
 * {@link AutoWorkState} via TLM's {@code TaskDataRegister} (T-2 A2).
 *
 * <p>Kept as a separate non-extension class so the existing
 * {@code @LittleMaidExtension} annotated entry point does not need to
 * expose the key type. Registration is performed by
 * {@code SincerelyExtension#registerTaskData}.
 */
public final class AutoWorkTaskDataKeys {
    public static final String MOD_ID = "tlm_sincerely";
    public static final ResourceLocation STATE_KEY_ID =
            new ResourceLocation(MOD_ID, "auto_work_state");

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
        tag.putBoolean("enabled", data.enabled());
        tag.putUUID("presetId", data.presetId() == null
                ? AutoWorkPresetIO.LoadedLibrary.makeDefault().getId() : data.presetId());
        tag.putInt("revision", data.revision());
        return tag;
    }

    private static AutoWorkState readTag(CompoundTag tag) {
        boolean enabled = tag.contains("enabled") && tag.getBoolean("enabled");
        UUID presetId;
        if (tag.hasUUID("presetId")) {
            presetId = tag.getUUID("presetId");
        } else {
            presetId = AutoWorkPresetIO.LoadedLibrary.makeDefault().getId();
        }
        int revision = tag.contains("revision") ? tag.getInt("revision") : 0;
        return new AutoWorkState(enabled, presetId, revision);
    }
}
