package com.github.tartaricacid.tlm_sincerely.priority.autowork.network;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Centralised permission checks for auto work switch packet handlers (T-2 A3).
 *
 * <p>Two policies coexist:
 * <ul>
 *   <li>Maid-targeted operations ({@link #canControlMaid}) require the
 *       player to be the maid owner or a level-2 operator. This matches
 *       the existing maid GUI ownership contract.</li>
 *   <li>Preset library operations ({@link #canEditLibrary}) require a
 *       level-2 operator, because preset changes are server-wide and
 *       can affect every maid.</li>
 * </ul>
 */
public final class AutoWorkPermission {
    private AutoWorkPermission() {
    }

    /** True if {@code player} is allowed to read/control {@code maid}. */
    public static boolean canControlMaid(@Nullable ServerPlayer player, @Nullable EntityMaid maid) {
        if (player == null || maid == null) {
            return false;
        }
        if (maid.isOwnedBy(player)) {
            return true;
        }
        if (player.hasPermissions(2)) {
            LivingEntity owner = maid.getOwner();
            // Defensive: refuse to control a maid whose level is on a
            // different server instance.
            return owner == null || owner.level().getServer() == player.server;
        }
        return false;
    }

    /** True if {@code player} is allowed to mutate the shared preset library. */
    public static boolean canEditLibrary(@Nullable ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return player.hasPermissions(2);
    }

    /**
     * Resolves an entity by its server-level UUID, or returns null if the
     * entity is missing or not an {@link EntityMaid}.
     */
    @Nullable
    public static EntityMaid resolveMaid(ServerPlayer player, java.util.UUID maidId) {
        if (player == null) {
            return null;
        }
        ServerLevel level = player.serverLevel();
        if (level.getEntity(maidId) instanceof EntityMaid maid) {
            return maid;
        }
        // Maid may be on another dimension; check the player's own server
        // for any matching maid. (getEntity on a non-existent UUID is O(1)
        // and returns null, so this loop is cheap.)
        for (ServerLevel other : player.server.getAllLevels()) {
            if (other == level) {
                continue;
            }
            if (other.getEntity(maidId) instanceof EntityMaid maid) {
                return maid;
            }
        }
        return null;
    }
}
