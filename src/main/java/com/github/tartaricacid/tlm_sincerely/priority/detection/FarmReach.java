package com.github.tartaricacid.tlm_sincerely.priority.detection;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidPathFindingBFS;
import net.minecraft.core.BlockPos;

/**
 * Reachability helpers matching TLM's own farm movement tasks.
 *
 * <p>{@code MaidFarmMoveTask} (the default {@code IFarmTask} brain) accepts a
 * single {@link MaidPathFindingBFS#canPathReach(BlockPos)};
 * {@code MaidFarmSurroundingMoveTask} (used by TLM's {@code cocoa} and
 * {@code melon}) accepts any standable position inside the
 * {@code (-1,0,-1)..(1,1,1)} offset box.
 *
 * <p>Do not use {@code EntityMaid#canPathReach} for crops: its A* requires the
 * target cell itself to be a path node, while sweet berry bushes, cocoa and
 * leaves are {@code DAMAGE_OTHER} / {@code COCOA} / {@code LEAVES} with malus
 * -1 and therefore never become nodes.
 */
public final class FarmReach {
    private static final int SURROUND_HORIZONTAL_MIN = -1;
    private static final int SURROUND_HORIZONTAL_MAX = 1;
    private static final int SURROUND_VERTICAL_MAX = 1;

    private FarmReach() {
    }

    /** Mirrors the reachability check of the movement task that will run for this crop. */
    public static boolean canReach(MaidPathFindingBFS arrivalMap, BlockPos pos, boolean surrounding) {
        if (!surrounding) {
            return arrivalMap.canPathReach(pos);
        }
        for (int x = SURROUND_HORIZONTAL_MIN; x <= SURROUND_HORIZONTAL_MAX; x++) {
            for (int y = 0; y <= SURROUND_VERTICAL_MAX; y++) {
                for (int z = SURROUND_HORIZONTAL_MIN; z <= SURROUND_HORIZONTAL_MAX; z++) {
                    if (arrivalMap.canPathReach(pos.offset(x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
