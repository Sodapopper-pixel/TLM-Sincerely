package com.github.tartaricacid.tlm_sincerely.priority.detection.compat;

import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionContext;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionResult;
import com.github.tartaricacid.tlm_sincerely.priority.detection.FarmReach;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskScanCursor;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskWorkDetector;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidPathFindingBFS;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.wallev.maidsoulkitchen.api.task.v1.farm.ICompatFarm;
import com.github.wallev.maidsoulkitchen.api.task.v1.farm.ICompatFarmHandler;
import com.github.wallev.maidsoulkitchen.entity.passive.IAddonMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * UID-exact detector for the MaidSoulKitchen berry farm task
 * ({@code maidsoulkitchen:berries_farm}, verified against the 1.21.1-beta-0.1.4
 * jar: {@code TaskBerryFarm.getUid()} returns that id).
 *
 * <p>MSK berry tasks do NOT implement TLM's {@code IFarmTask}: they extend
 * {@link ICompatFarm}, whose harvest contract is the per-maid handler
 * chain resolved by {@code getCompatHandler(EntityMaid)}. The generic
 * {@code FarmTaskWorkDetector} (base-pos + seed/canPlant semantics) is
 * therefore inapplicable — MSK scans the crop block itself and has no
 * planting/seed API for detection.
 *
 * <p>Scan geometry mirrors the runtime AI ({@code MaidCompatFarmMoveTask}
 * over TLM's {@code MaidMoveToBlockTask}): center = home center or maid
 * position, horizontal range = restriction radius, vertical search range =
 * 2 whose y-1 offset sequence ({@code -1, 0, -2, 1, -3}) is exactly
 * {@link TaskScanCursor}'s verticalOffset, and the same
 * restriction/owner/path gates.
 *
 * <p>Reachability uses the same surrounding check as the MSK berry brain
 * ({@code TaskBerryFarm}'s 3x2x3 box) rather than a single-point query; see
 * {@link FarmReach}.
 *
 * <p>The verdict is read-only: {@code ICompatFarmHandler.shouldMoveTo}
 * (the handler chain's canHarvest, no world mutation) plus a live query of
 * {@link IAddonMaid#BLACK_LIST} (1.21.1 renamed the constant from the old
 * {@code ICompatFarmTask.BLACK_LIST}; {@code TaskBerryFarm.canHarvest} applies
 * the same conjunction) — the exact conjunction used by the AI's
 * {@code shouldMoveTo}. The handler chain is resolved once per
 * detect call and reused for the whole scan window; it is never stored in
 * static or instance state (the detector is a shared singleton), so no
 * collection leak and rule changes are picked up conservatively on the
 * next detection cycle.
 */
public final class MaidSoulKitchenBerryDetector implements TaskWorkDetector {
    public static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath("maidsoulkitchen", "berries_farm");

    /** Matches TLM {@code MaidMoveToBlockTask}'s verticalSearchRange=2. */
    private static final int FARM_VERTICAL_RANGE = 2;

    @Override
    public boolean supports(IMaidTask task) {
        return UID.equals(task.getUid()) && task instanceof ICompatFarm<?, ?>;
    }

    @Override
    public boolean usesBlockBudget() {
        return true;
    }

    @Override
    public DetectionResult detect(DetectionContext context, IMaidTask task) {
        if (!(task instanceof ICompatFarm<?, ?> compatTask)) {
            return DetectionResult.unknown(task.getUid(), context.currentTick(), "NOT_COMPAT_FARM_TASK");
        }
        EntityMaid maid = context.maid();
        if (!task.isEnable(maid)) {
            context.setCursor(null);
            return unavailable(context, task, "TASK_DISABLED");
        }

        boolean homeMode = maid.isHomeModeEnable();
        BlockPos center = homeMode ? maid.getRestrictCenter() : maid.blockPosition();
        int horizontalRange = Math.max(0, (int) maid.getRestrictRadius() - 1);
        TaskScanCursor cursor = context.cursor();
        if (cursor == null || !cursor.matches(center, homeMode, horizontalRange, FARM_VERTICAL_RANGE)) {
            // MSK berry has no planting semantics; the seed list stays empty.
            cursor = TaskScanCursor.start(center, homeMode, horizontalRange, FARM_VERTICAL_RANGE,
                    context.currentTick(), List.of());
        }
        int unreachableCandidates = cursor.unreachableCandidates();

        // Resolve the per-maid handler chain once per detect call and reuse it
        // across the whole scan window. Re-resolving every block would allocate a
        // Builder chain per block; caching it in fields would leak shared-instance
        // state. Rule (BerryData) changes therefore take effect at worst one
        // minIntervalTicks later — the conservative trade-off.
        ICompatFarmHandler handler = compatTask.getCompatHandler(maid);
        if (handler == null) {
            context.setCursor(null);
            return unavailable(context, task, "NO_COMPAT_HANDLER");
        }

        MaidPathFindingBFS arrivalMap = new MaidPathFindingBFS(
                maid.getNavigation().getNodeEvaluator(), context.level(), maid);
        try {
            while (cursor != null && context.consumeBlock()) {
                // MSK scans the crop block itself (sweet-berry bush), not base.above().
                BlockPos cropPos = cursor.currentPos();
                TaskScanCursor nextCursor = cursor.advance();

                if (!maid.isWithinRestriction(cropPos) || !isNearOwner(maid, cropPos)) {
                    cursor = nextCursor;
                    continue;
                }
                BlockState cropState = context.level().getBlockState(cropPos);
                if (IAddonMaid.BLACK_LIST.contains(cropState.getBlock())
                        || !handler.shouldMoveTo(maid, cropPos, cropState)) {
                    cursor = nextCursor;
                    continue;
                }
                if (!context.consumePathCheck()) {
                    // Retry the same candidate next tick instead of skipping it for this round.
                    context.setCursor(cursor);
                    return DetectionResult.unknown(task.getUid(), context.currentTick(), "PATH_BUDGET_EXHAUSTED");
                }
                // MSK's berry brain uses TaskBerryFarm's surrounding check: the bush
                // itself is DAMAGE_OTHER (malus -1) and can never be a path node, so a
                // single-point check would always fail.
                if (FarmReach.canReach(arrivalMap, cropPos, true)) {
                    context.setCursor(null);
                    return new DetectionResult(task.getUid(), Availability.AVAILABLE, context.currentTick(),
                            40, 0, "HARVESTABLE_BERRY", cropPos, null);
                }
                unreachableCandidates++;
                cursor = nextCursor == null ? null : nextCursor.withUnreachableCandidates(unreachableCandidates);
            }
        } finally {
            arrivalMap.finish();
        }

        context.setCursor(cursor);
        if (cursor == null) {
            return unavailable(context, task,
                    unreachableCandidates > 0 ? "FULL_SCAN_ALL_UNREACHABLE" : "FULL_SCAN_EMPTY");
        }
        return DetectionResult.unknown(task.getUid(), context.currentTick(), "BLOCK_BUDGET_EXHAUSTED");
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
