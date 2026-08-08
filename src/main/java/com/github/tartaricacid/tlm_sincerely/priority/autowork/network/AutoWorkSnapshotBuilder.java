package com.github.tartaricacid.tlm_sincerely.priority.autowork.network;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPreset;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPresetService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkState;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkStateService;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-side builder for {@link AutoWorkSnapshot} (T-2 A3).
 *
 * <p>Reads only from the bound per-server services. Must be called from
 * the server thread. Maids visible to the requesting player (own + OP
 * fallback) are projected; non-visible maids are silently skipped.
 */
public final class AutoWorkSnapshotBuilder {
    private AutoWorkSnapshotBuilder() {
    }

    public static AutoWorkSnapshot build(MinecraftServer server, ServerPlayer requester) {
        AutoWorkPresetService presetService = AutoWorkPresetService.getOrNull(server);
        AutoWorkStateService stateService = AutoWorkStateService.getOrNull(server);

        int revision = 0;
        List<AutoWorkSnapshot.PresetEntry> presets = new ArrayList<>();
        UUID defaultPresetId;
        if (presetService != null) {
            for (AutoWorkPreset preset : presetService.listPresets()) {
                presets.add(new AutoWorkSnapshot.PresetEntry(
                        preset.getId(),
                        preset.getName(),
                        preset.getOrder()
                ));
                revision = Math.max(revision, preset.getOrder().size() + preset.getName().hashCode());
            }
            defaultPresetId = presetService.getDefaultPresetId();
        } else {
            // Defensive: server lifecycle race. The IO helper has a stable
            // default id we can fall back to without throwing.
            defaultPresetId = com.github.tartaricacid.tlm_sincerely.priority.autowork
                    .AutoWorkPresetIO.LoadedLibrary.makeDefault().getId();
        }
        // Revision mixes preset count and default id so GUI caches notice
        // structural changes; the per-maid revision field gives finer
        // granularity for individual rows.
        revision = (revision * 31) + defaultPresetId.hashCode();

        List<AutoWorkSnapshot.MaidEntry> maidEntries = new ArrayList<>();
        if (stateService != null) {
            for (ServerLevel level : server.getAllLevels()) {
                // Walk every loaded entity; EntityMaid is uncommon enough
                // that the filter is cheap. We avoid AABB tricks because
                // the world border may be smaller than the loaded chunks.
                for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                    if (!(entity instanceof EntityMaid maid)) {
                        continue;
                    }
                    if (!isVisibleTo(maid, requester)) {
                        continue;
                    }
                    AutoWorkState state = stateService.getState(maid);
                    maidEntries.add(new AutoWorkSnapshot.MaidEntry(
                            maid.getUUID(),
                            state.enabled(),
                            state.presetId(),
                            state.revision()
                    ));
                    revision = Math.max(revision, state.revision());
                }
            }
        }

        return new AutoWorkSnapshot(revision, defaultPresetId, presets, maidEntries);
    }

    private static boolean isVisibleTo(EntityMaid maid, ServerPlayer player) {
        if (maid == null || player == null) {
            return false;
        }
        if (maid.isOwnedBy(player)) {
            return true;
        }
        // OP fallback: a level 2 operator may inspect / control any maid,
        // matching the policy used by the rest of the auto work flow.
        if (player.hasPermissions(2)) {
            LivingEntity owner = maid.getOwner();
            return owner == null || owner.level().getServer() == player.server;
        }
        return false;
    }
}
