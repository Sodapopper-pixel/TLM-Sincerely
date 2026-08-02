package com.github.tartaricacid.tlm_sincerely.priority.detection;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;

public final class DetectionContext {
    private final EntityMaid maid;
    private final ServerLevel level;
    private final long currentTick;
    private int remainingBlockBudget;
    private int remainingPathBudget;
    private int consumedBlocks;
    private int consumedPaths;
    private TaskScanCursor cursor;

    public DetectionContext(EntityMaid maid, ServerLevel level, long currentTick,
                            int remainingBlockBudget, int remainingPathBudget, TaskScanCursor cursor) {
        this.maid = maid;
        this.level = level;
        this.currentTick = currentTick;
        this.remainingBlockBudget = remainingBlockBudget;
        this.remainingPathBudget = remainingPathBudget;
        this.cursor = cursor;
    }

    public EntityMaid maid() {
        return maid;
    }

    public ServerLevel level() {
        return level;
    }

    public long currentTick() {
        return currentTick;
    }

    public TaskScanCursor cursor() {
        return cursor;
    }

    public void setCursor(TaskScanCursor cursor) {
        this.cursor = cursor;
    }

    public boolean consumeBlock() {
        if (remainingBlockBudget <= 0) {
            return false;
        }
        remainingBlockBudget--;
        consumedBlocks++;
        return true;
    }

    public boolean consumePathCheck() {
        if (remainingPathBudget <= 0) {
            return false;
        }
        remainingPathBudget--;
        consumedPaths++;
        return true;
    }

    public int consumedBlocks() {
        return consumedBlocks;
    }

    public int consumedPaths() {
        return consumedPaths;
    }
}
