package com.github.tartaricacid.tlm_sincerely.priority.detection;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Persistent state for one incremental farm scan. Seed stacks are copies made
 * at scan start so the detector never retains mutable inventory references.
 */
public record TaskScanCursor(
        BlockPos center,
        boolean homeMode,
        int horizontalRange,
        int verticalRange,
        int verticalIndex,
        int ring,
        int ringStep,
        long startedTick,
        List<ItemStack> seeds,
        int unreachableCandidates
) {
    public TaskScanCursor {
        seeds = List.copyOf(seeds);
    }

    public static TaskScanCursor start(BlockPos center, boolean homeMode, int horizontalRange, int verticalRange,
                                       long startedTick, List<ItemStack> seeds) {
        return new TaskScanCursor(center, homeMode, horizontalRange, verticalRange,
                0, 0, 0, startedTick, seeds, 0);
    }

    public boolean matches(BlockPos newCenter, boolean newHomeMode, int newHorizontalRange, int newVerticalRange) {
        return homeMode == newHomeMode && horizontalRange == newHorizontalRange && verticalRange == newVerticalRange;
    }

    public BlockPos currentPos() {
        int x = 0;
        int z = 0;
        if (ring > 0) {
            if (ringStep <= ring * 2) {
                x = -ring + ringStep;
                z = -ring;
            } else if (ringStep <= ring * 4) {
                x = ring;
                z = -ring + ringStep - ring * 2;
            } else if (ringStep <= ring * 6) {
                x = ring - (ringStep - ring * 4);
                z = ring;
            } else {
                x = -ring;
                z = ring - (ringStep - ring * 6);
            }
        }
        return center.offset(x, verticalOffset(), z);
    }

    private int verticalOffset() {
        if (verticalIndex == 0) {
            return -1;
        }
        int distance = (verticalIndex + 1) / 2;
        int searchY = verticalIndex % 2 == 1 ? distance : -distance;
        return searchY - 1;
    }

    public TaskScanCursor withUnreachableCandidates(int count) {
        return new TaskScanCursor(center, homeMode, horizontalRange, verticalRange, verticalIndex,
                ring, ringStep, startedTick, seeds, count);
    }

    public TaskScanCursor advance() {
        if (ring == 0 && horizontalRange > 0) {
            return new TaskScanCursor(center, homeMode, horizontalRange, verticalRange, verticalIndex,
                    1, 0, startedTick, seeds, unreachableCandidates);
        }
        if (ring > 0 && ringStep + 1 < ring * 8) {
            return new TaskScanCursor(center, homeMode, horizontalRange, verticalRange, verticalIndex,
                    ring, ringStep + 1, startedTick, seeds, unreachableCandidates);
        }
        if (ring < horizontalRange) {
            return new TaskScanCursor(center, homeMode, horizontalRange, verticalRange, verticalIndex,
                    ring + 1, 0, startedTick, seeds, unreachableCandidates);
        }
        if (verticalIndex >= verticalRange * 2) {
            return null;
        }
        return new TaskScanCursor(center, homeMode, horizontalRange, verticalRange, verticalIndex + 1,
                0, 0, startedTick, seeds, unreachableCandidates);
    }
}
