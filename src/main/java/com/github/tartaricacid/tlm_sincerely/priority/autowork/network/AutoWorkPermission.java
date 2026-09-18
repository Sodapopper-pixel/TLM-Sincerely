package com.github.tartaricacid.tlm_sincerely.priority.autowork.network;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Centralised permission checks for auto work packet handlers.
 *
 * <p>The preset library is private to each client, so there is no library
 * permission any more: only maid-targeted operations remain, and those require
 * the player to be the maid owner or a level-2 operator (matching the maid GUI
 * ownership contract). Client-side push commands are additionally limited by
 * the server-side broadcast check in {@code AutoWorkPushService}.
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
