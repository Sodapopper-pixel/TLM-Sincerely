package com.github.tartaricacid.tlm_sincerely.chatbar;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

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

    public static FindResult findByName(ServerPlayer player, String name) {
        List<EntityMaid> maids = getOwnedMaids(player);
        String lowerName = name.toLowerCase().trim();

        List<EntityMaid> exactMatches = new ArrayList<>();
        List<EntityMaid> fuzzyMatches = new ArrayList<>();

        for (EntityMaid maid : maids) {
            String maidName = maid.getName().getString().toLowerCase();
            if (maidName.equals(lowerName)) {
                exactMatches.add(maid);
            } else if (maidName.contains(lowerName)) {
                fuzzyMatches.add(maid);
            }
        }

        List<EntityMaid> matches = exactMatches.isEmpty() ? fuzzyMatches : exactMatches;
        if (matches.isEmpty()) {
            return new FindResult(null, 0);
        }

        EntityMaid nearest = findNearest(player, matches);
        return new FindResult(nearest, matches.size());
    }

    @Nullable
    public static EntityMaid findByUuid(ServerPlayer player, String uuidString) {
        try {
            UUID uuid = UUID.fromString(uuidString);
            for (EntityMaid maid : getOwnedMaids(player)) {
                if (maid.getUUID().equals(uuid)) {
                    return maid;
                }
            }
        } catch (IllegalArgumentException e) {
            return null;
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

    public record FindResult(@Nullable EntityMaid maid, int matchCount) {
        public boolean hasMaid() {
            return maid != null;
        }

        public boolean hasMultipleMatches() {
            return matchCount > 1;
        }
    }
}