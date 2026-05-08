package com.github.tartaricacid.tlm_sincerely.chatbar;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.List;

public final class MaidFinder {
    private static final double DEFAULT_RANGE = 64.0;

    public static List<EntityMaid> getOwnedMaids(ServerPlayer player) {
        return getOwnedMaids(player, DEFAULT_RANGE);
    }

    public static List<EntityMaid> getOwnedMaids(ServerPlayer player, double range) {
        return player.level().getEntitiesOfClass(EntityMaid.class,
                player.getBoundingBox().inflate(range),
                maid -> maid.isOwnedBy(player) && maid.isAlive()
        );
    }

    public static EntityMaid findByName(ServerPlayer player, String name) {
        List<EntityMaid> maids = getOwnedMaids(player);
        String lowerName = name.toLowerCase().trim();

        for (EntityMaid maid : maids) {
            String maidName = maid.getName().getString().toLowerCase();
            if (maidName.equals(lowerName)) {
                return maid;
            }
        }

        for (EntityMaid maid : maids) {
            String maidName = maid.getName().getString().toLowerCase();
            if (maidName.contains(lowerName)) {
                return maid;
            }
        }

        return null;
    }

    public static EntityMaid findNearest(ServerPlayer player) {
        return findNearest(player, getOwnedMaids(player));
    }

    public static EntityMaid findNearest(ServerPlayer player, List<EntityMaid> maids) {
        if (maids.isEmpty()) {
            return null;
        }

        return maids.stream()
                .min(Comparator
                        .comparing((EntityMaid m) -> m.isHomeModeEnable())
                        .thenComparingDouble(m -> m.distanceToSqr(player))
                )
                .orElse(null);
    }
}