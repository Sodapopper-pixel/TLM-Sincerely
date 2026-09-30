package com.github.tartaricacid.tlm_sincerely.priority.autowork;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * Per-maid persistent state for the auto work switch.
 *
 * <p>The maid no longer points at a shared preset library entry: selecting a
 * preset bakes that preset's id/name/order into this state (the maid bound
 * snapshot, see {@code docs/adr/0004-client-preset-library-and-maid-bound-snapshot.md}).
 * The server-side scheduler reads only {@link #order()} from here, so later
 * edits of the client-side library or a pushed library never silently change
 * a maid that is already bound.
 *
 * <p>{@code snapshotBaked} distinguishes "this maid has never been bound"
 * (legacy data with only a preset id) from "bound to a preset whose order is
 * legitimately empty". {@code revision} is bumped on every mutation so cached
 * snapshots can detect changes.
 */
public record AutoWorkState(boolean enabled, UUID presetId, String presetName,
                            List<ResourceLocation> order, boolean snapshotBaked, int revision) {

    public static final UUID NO_PRESET_ID = new UUID(0L, 0L);

    public AutoWorkState {
        presetId = presetId == null ? NO_PRESET_ID : presetId;
        presetName = presetName == null ? "" : presetName;
        // 对齐快照包 encode 的 writeUtf(presetName, 64)：NBT 反序列化与 seed 默认
        // 预设两条路径都绕过网络包的 64 上限校验，超长名字会在服务端登录发包时
        // 抛异常并被 netty 断开连接（客户端表现为进服即被静默踢出）。
        if (presetName.length() > 64) {
            presetName = presetName.substring(0, 64);
        }
        order = order == null ? List.of() : List.copyOf(order);
    }

    /** A read-only fallback for maids that have never touched auto work. */
    public static AutoWorkState disabledFallback(UUID presetId, String presetName,
                                                 List<ResourceLocation> order) {
        return new AutoWorkState(false, presetId, presetName, order, false, 0);
    }

    public AutoWorkState withEnabled(boolean newEnabled) {
        return new AutoWorkState(newEnabled, this.presetId, this.presetName, this.order,
                this.snapshotBaked, this.revision + 1);
    }

    /** Replaces the whole bound snapshot with the given preset data. */
    public AutoWorkState withSnapshot(UUID newPresetId, String newPresetName,
                                      List<ResourceLocation> newOrder) {
        return new AutoWorkState(this.enabled, newPresetId, newPresetName, newOrder,
                true, this.revision + 1);
    }

    /** Replaces the bound order but keeps the preset identity. */
    public AutoWorkState withOrder(List<ResourceLocation> newOrder) {
        return new AutoWorkState(this.enabled, this.presetId, this.presetName, newOrder,
                true, this.revision + 1);
    }
}
