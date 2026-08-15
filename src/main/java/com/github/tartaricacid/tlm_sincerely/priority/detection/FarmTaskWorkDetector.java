package com.github.tartaricacid.tlm_sincerely.priority.detection;

import com.github.tartaricacid.touhoulittlemaid.api.task.IFarmTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;

public final class FarmTaskWorkDetector implements TaskWorkDetector {
    private static final int FARM_VERTICAL_RANGE = 2;

    @Override
    public boolean supports(IMaidTask task) {
        return task instanceof IFarmTask;
    }

    @Override
    public boolean usesBlockBudget() {
        return true;
    }

    @Override
    public DetectionResult detect(DetectionContext context, IMaidTask task) {
        if (!(task instanceof IFarmTask farmTask)) {
            return DetectionResult.unknown(task.getUid(), context.currentTick(), "NOT_FARM_TASK");
        }
        EntityMaid maid = context.maid();
        if (!task.isEnable(maid)) {
            context.setCursor(null);
            return unavailable(context, task, "TASK_DISABLED");
        }

        boolean homeMode = maid.isHomeModeEnable();
        BlockPos center = homeMode ? maid.getRestrictCenter() : maid.blockPosition();
        int horizontalRange = Math.max(0, (int) maid.getRestrictRadius());
        TaskScanCursor cursor = context.cursor();
        if (cursor == null || !cursor.matches(center, homeMode, horizontalRange, FARM_VERTICAL_RANGE)) {
            cursor = TaskScanCursor.start(center, homeMode, horizontalRange, FARM_VERTICAL_RANGE,
                    context.currentTick(), collectSeeds(maid, farmTask));
        }
        int unreachableCandidates = cursor.unreachableCandidates();

        while (cursor != null && context.consumeBlock()) {
            BlockPos basePos = cursor.currentPos();
            TaskScanCursor nextCursor = cursor.advance();

            if (!maid.isWithinRestriction(basePos) || !isNearOwner(maid, basePos)) {
                cursor = nextCursor;
                continue;
            }
            if (farmTask.checkCropPosAbove()
                    && !context.level().getBlockState(basePos.above(2))
                    .getCollisionShape(context.level(), basePos.above(2)).isEmpty()) {
                cursor = nextCursor;
                continue;
            }

            BlockPos cropPos = basePos.above();
            BlockState cropState = context.level().getBlockState(cropPos);
            BlockState baseState = context.level().getBlockState(basePos);
            boolean canHarvest = farmTask.canHarvest(maid, cropPos, cropState);
            boolean canPlant = !canHarvest && canPlant(farmTask, maid, basePos, baseState, cursor.seeds());
            if (!canHarvest && !canPlant) {
                cursor = nextCursor;
                continue;
            }
            if (!context.consumePathCheck()) {
                context.setCursor(nextCursor);
                return DetectionResult.unknown(task.getUid(), context.currentTick(), "PATH_BUDGET_EXHAUSTED");
            }
            if (maid.canPathReach(basePos)) {
                context.setCursor(null);
                return new DetectionResult(task.getUid(), Availability.AVAILABLE, context.currentTick(),
                        40, 0, canHarvest ? "HARVESTABLE_CROP" : "PLANTABLE_CROP", basePos, null);
            }
            unreachableCandidates++;
            cursor = nextCursor == null ? null : nextCursor.withUnreachableCandidates(unreachableCandidates);
        }

        context.setCursor(cursor);
        if (cursor == null) {
            return unavailable(context, task,
                    unreachableCandidates > 0 ? "FULL_SCAN_ALL_UNREACHABLE" : "FULL_SCAN_EMPTY");
        }
        return DetectionResult.unknown(task.getUid(), context.currentTick(), "BLOCK_BUDGET_EXHAUSTED");
    }

    private static List<ItemStack> collectSeeds(EntityMaid maid, IFarmTask farmTask) {
        IItemHandler inventory = maid.getAvailableInv(true);
        List<ItemStack> seeds = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && farmTask.isSeed(stack)) {
                seeds.add(stack.copy());
            }
        }
        return seeds;
    }

    private static boolean canPlant(IFarmTask farmTask, EntityMaid maid, BlockPos basePos,
                                    BlockState baseState, List<ItemStack> seeds) {
        for (ItemStack seed : seeds) {
            if (farmTask.canPlant(maid, basePos, baseState, seed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNearOwner(EntityMaid maid, BlockPos pos) {
        if (maid.isHomeModeEnable()) {
            return true;
        }
        LivingEntity owner = maid.getOwner();
        return owner != null && pos.closerToCenterThan(owner.position(), 8);
    }

    private static DetectionResult unavailable(DetectionContext context, IMaidTask task, String evidence) {
        return new DetectionResult(task.getUid(), Availability.UNAVAILABLE, context.currentTick(),
                40, 0, evidence, null, null);
    }
}
