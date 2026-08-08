package com.github.tartaricacid.tlm_sincerely.priority.autowork;

import java.util.UUID;

/**
 * Per-maid persistent state for the auto work switch (T-2 A2).
 *
 * <p>Stores whether the maid is currently managed by the auto work switch
 * and which preset the maid has selected. The {@code revision} field is bumped
 * on every server-side mutation so cached snapshots can detect changes.
 *
 * <p>This class is independent of the maid's real {@code IMaidTask}; the
 * auto work switcher can keep this state untouched while the real task
 * changes underneath (see plan §4.1).
 */
public record AutoWorkState(boolean enabled, UUID presetId, int revision) {

    public static final AutoWorkState DISABLED_FALLBACK =
            new AutoWorkState(false, new UUID(0L, 0L), 0);

    public AutoWorkState withEnabled(boolean newEnabled) {
        return new AutoWorkState(newEnabled, this.presetId, this.revision + 1);
    }

    public AutoWorkState withPresetId(UUID newPresetId) {
        return new AutoWorkState(this.enabled, newPresetId, this.revision + 1);
    }
}
