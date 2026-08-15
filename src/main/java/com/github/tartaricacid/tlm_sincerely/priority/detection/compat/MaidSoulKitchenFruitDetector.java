package com.github.tartaricacid.tlm_sincerely.priority.detection.compat;

import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionContext;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionResult;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskScanCursor;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskWorkDetector;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.wallev.maidsoulkitchen.api.task.farm.ICompatFarmHandler;
import com.github.wallev.maidsoulkitchen.api.task.farm.ICompatFarmTask;
import com.github.wallev.maidsoulkitchen.entity.data.inner.task.berryfruit.v1.BerryFruitData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * UID-exact detector for the MaidSoulKitchen fruit farm task
 * ({@code maidsoulkitchen:fruit_farm}, verified against the 0.3.0.9 jar:
 * {@code TaskFruitFarm.getUid()} returns that id).
 *
 * <p>Maturity is delegated to MSK's public per-maid compat handler chain so
 * Simple Farming fruit leaves and future registered handlers keep their own
 * verified rules.
 *
 * <p>Scan geometry mirrors the runtime AI ({@code MaidCompatFruitMoveTask}):
 * center = home center or maid position, horizontal range = restriction
 * radius, and the checked layer starts at the per-maid searchYOffset. The
 * TaskScanCursor center is shifted so its five layers match MSK's linear
 * {@code offset..offset+4} scan exactly.
 *
 * <p>Harvest-only semantics: no tool requirement and no planting detection.
 */
public final class MaidSoulKitchenFruitDetector implements TaskWorkDetector {
    public static final ResourceLocation UID = new ResourceLocation("maidsoulkitchen", "fruit_farm");

    /** verticalSearchRange=2 produces five checked layers. */
    private static final int FRUIT_VERTICAL_RANGE = 2;

    @Override
    public boolean supports(IMaidTask task) {
        return UID.equals(task.getUid()) && task instanceof ICompatFarmTask<?>;
    }

    @Override
    public boolean usesBlockBudget() {
        return true;
    }

    @Override
    public DetectionResult detect(DetectionContext context, IMaidTask task) {
        EntityMaid maid = context.maid();
        if (!task.isEnable(maid)) {
            context.setCursor(null);
            return unavailable(context, task, "TASK_DISABLED");
        }
        if (!(task instanceof ICompatFarmTask<?> compatTask)) {
            return DetectionResult.unknown(task.getUid(), context.currentTick(), "NOT_COMPAT_FARM_TASK");
        }
        ICompatFarmHandler handler = compatTask.getCompatHandler(maid);
        if (handler == null) {
            context.setCursor(null);
            return unavailable(context, task, "NO_COMPAT_HANDLER");
        }
        BerryFruitData data = compatTask.getTaskData(maid);
        int searchYOffset = data == null ? 3 : data.searchYOffset();

        boolean homeMode = maid.isHomeModeEnable();
        BlockPos center = homeMode ? maid.getRestrictCenter() : maid.blockPosition();
        int horizontalRange = Math.max(0, (int) maid.getRestrictRadius() - 1);
        BlockPos scanCenter = center.above(searchYOffset + FRUIT_VERTICAL_RANGE + 1);
        TaskScanCursor cursor = context.cursor();
        if (cursor == null || !cursor.matches(scanCenter, homeMode, horizontalRange, FRUIT_VERTICAL_RANGE)) {
            cursor = TaskScanCursor.start(scanCenter, homeMode, horizontalRange, FRUIT_VERTICAL_RANGE,
                    context.currentTick(), List.of());
        }
        int unreachableCandidates = cursor.unreachableCandidates();

        while (cursor != null && context.consumeBlock()) {
            // Cursor position is already the checked fruit block (Y+3..Y+7 band).
            BlockPos cropPos = cursor.currentPos();
            TaskScanCursor nextCursor = cursor.advance();

            if (!maid.isWithinRestriction(cropPos) || !isNearOwner(maid, cropPos)) {
                cursor = nextCursor;
                continue;
            }
            BlockState cropState = context.level().getBlockState(cropPos);
            if (!canHarvest(compatTask, maid, cropPos, cropState, handler)) {
                cursor = nextCursor;
                continue;
            }
            if (!context.consumePathCheck()) {
                context.setCursor(nextCursor);
                return DetectionResult.unknown(task.getUid(), context.currentTick(), "PATH_BUDGET_EXHAUSTED");
            }
            if (maid.canPathReach(cropPos)) {
                context.setCursor(null);
                return new DetectionResult(task.getUid(), Availability.AVAILABLE, context.currentTick(),
                        40, 0, "MATURE_FRUIT", cropPos, null);
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean canHarvest(ICompatFarmTask<?> task, EntityMaid maid, BlockPos pos,
                                      BlockState state, ICompatFarmHandler handler) {
        return ((ICompatFarmTask) task).canHarvest(maid, pos, state, handler);
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
