package com.github.tartaricacid.tlm_sincerely.priority.autowork.network;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * Server-authoritative snapshot of the auto work switch state (T-2 A3).
 *
 * <p>This DTO is the only thing the client GUI is allowed to see. It
 * intentionally omits any internal flags, file paths, or revision numbers
 * that are not useful to the GUI: a single {@link #revision()} is used
 * to drive client-side cache invalidation, and a {@link #defaultPresetId()}
 * lets the GUI select the default preset when a maid's stored id is
 * stale.
 *
 * <p>Per-maid entries are filtered to the ones the requesting player is
 * allowed to see (own + OP-visible, see
 * {@link AutoWorkSnapshotBuilder#build(MinecraftServer, ServerPlayer)}).
 */
public record AutoWorkSnapshot(
        int revision,
        UUID defaultPresetId,
        List<PresetEntry> presets,
        List<MaidEntry> maids
) {
    /** Single preset projection. */
    public record PresetEntry(UUID id, String name, List<ResourceLocation> order) {
    }

    /**
     * Single maid projection. Only maids the requesting player is allowed
     * to see (own + OP) are emitted by the server.
     */
    public record MaidEntry(
            UUID maidId,
            boolean enabled,
            UUID presetId,
            int stateRevision
    ) {
    }
}
