package com.github.tartaricacid.tlm_sincerely.priority.autowork.network;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkState;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkStateService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.compat.AutoWorkCompatService;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side builder for {@link AutoWorkSnapshot}.
 *
 * <p>Reads only the bound per-maid state and the compat report; the preset
 * library is never included. Must be called from the server thread. Maids
 * visible to the requesting player (own + OP fallback) are projected.
 */
public final class AutoWorkSnapshotBuilder {
    private AutoWorkSnapshotBuilder() {
    }

    public static AutoWorkSnapshot build(MinecraftServer server, ServerPlayer requester) {
        AutoWorkStateService stateService = AutoWorkStateService.getOrNull(server);

        int revision = 0;
        List<AutoWorkSnapshot.CompatEntry> compatEntries = new ArrayList<>();
        AutoWorkCompatService compatService = AutoWorkCompatService.getOrNull(server);
        if (compatService != null) {
            for (AutoWorkCompatService.ReportEntry entry : compatService.getEntries()) {
                compatEntries.add(new AutoWorkSnapshot.CompatEntry(entry.uid(), entry.level().name(), entry.reason()));
                revision = (revision * 31) + entry.hashCode();
            }
        }

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
                            state.presetName(),
                            List.copyOf(state.order()),
                            state.snapshotBaked(),
                            state.revision()
                    ));
                    revision = Math.max(revision, state.revision());
                }
            }
        }

        return new AutoWorkSnapshot(revision, compatEntries, maidEntries);
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
