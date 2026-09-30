package com.github.tartaricacid.tlm_sincerely.releasewire;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * Server-authoritative snapshot of the auto work switch state.
 *
 * <p>The preset library itself is private to each client and is deliberately
 * NOT part of this snapshot; the client GUI combines its local library with
 * the per-maid {@link MaidEntry} bound snapshot (see
 * {@code docs/adr/0004-client-preset-library-and-maid-bound-snapshot.md}).
 * Detector compat levels stay server-side and are shipped through
 * {@link CompatEntry}.
 */
public record AutoWorkSnapshot(
        int revision,
        List<CompatEntry> compatEntries,
        List<MaidEntry> maids
) {
    /** Server-classified detector coverage used for client-only task labels. */
    public record CompatEntry(ResourceLocation taskUid, String level, String reason) {
    }

    /**
     * Single maid projection with the full bound snapshot the scheduler reads.
     * Only maids the requesting player is allowed to see are emitted.
     */
    public record MaidEntry(
            UUID maidId,
            boolean enabled,
            UUID presetId,
            String presetName,
            List<ResourceLocation> order,
            boolean snapshotBaked,
            int stateRevision
    ) {
    }
}
