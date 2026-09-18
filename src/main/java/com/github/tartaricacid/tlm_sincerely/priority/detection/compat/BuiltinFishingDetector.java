package com.github.tartaricacid.tlm_sincerely.priority.detection.compat;

import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionContext;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionResult;
import com.github.tartaricacid.tlm_sincerely.priority.detection.HardToolRequirement;
import com.github.tartaricacid.tlm_sincerely.priority.detection.MaidHardToolService;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskScanCursor;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskWorkDetector;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityChair;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraftforge.common.ToolActions;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Read-only equivalent of TLM 1.5.3 TaskFishing + MaidFindSitTask + MaidRideFindWaterTask.
 * Never mounts a seat, casts a hook, or writes brain memory.
 *
 * <p>Tool precondition: a castable fishing rod in the main hand <b>or</b> the
 * backpack (TLM's own {@code onFunctionCallSwitch} equips from the backpack;
 * the automatic switch path must call {@link MaidHardToolService#equipTaskRequirement}
 * before {@code setTask} so the rod lands in the main hand).
 *
 * <p>Riding maids run the 6 x 3 water scan of {@code MaidRideFindWaterTask(6, 3)}
 * (horizontal rings up to 5, vertical layers {@code -1, 0, -2, 1, -3, 2, -4} — the
 * same {@link TaskScanCursor} offsets as TLM's {@code y - 1} loop). Idle maids look
 * for an unoccupied visible {@link EntityChair} like {@code MaidFindSitTask}.
 * The water scan is fully block-budgeted.
 */
public final class BuiltinFishingDetector implements TaskWorkDetector {
    public static final ResourceLocation UID = new ResourceLocation("touhou_little_maid", "fishing");
    /** 硬性工具：可抛竿的钓鱼竿，主手或背包存在即可（装备动作由 {@link MaidHardToolService} 完成）。 */
    public static final HardToolRequirement REQUIRED_TOOL = new HardToolRequirement(
            "fishing_rod", stack -> stack.canPerformAction(ToolActions.FISHING_ROD_CAST));
    private static final int WATER_SEARCH_RANGE = 6;
    private static final int WATER_VERTICAL_RANGE = 3;
    private static final double SIT_CLOSE_ENOUGH_SQR = 4.0D;
    private static final double SEAT_SEARCH_RANGE = 8.0D;

    @Override
    public boolean supports(IMaidTask task) {
        return UID.equals(task.getUid());
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
        if (!MaidHardToolService.hasAny(maid, REQUIRED_TOOL)) {
            context.setCursor(null);
            return unavailable(context, task, "FISHING_ROD_REQUIRED");
        }
        // A cast hook means fishing is already in progress; keep the task instead of hunting new targets.
        if (maid.hasFishingHook()) {
            context.setCursor(null);
            return new DetectionResult(task.getUid(), Availability.AVAILABLE, context.currentTick(),
                    40, 0, "HOOK_ACTIVE", null, null);
        }
        if (maid.getVehicle() != null) {
            return detectRideWater(context, task, maid);
        }
        return detectSeat(context, task, maid);
    }

    /** MaidRideFindWaterTask(6, 3): incremental water scan, block-budgeted, no path checks (TLM never walks to the hook spot). */
    private static DetectionResult detectRideWater(DetectionContext context, IMaidTask task, EntityMaid maid) {
        return detectWater(context, task, maid, maid.getBrainSearchPos());
    }

    private static DetectionResult detectWater(DetectionContext context, IMaidTask task,
                                               EntityMaid maid, BlockPos center) {
        boolean homeMode = maid.isHomeModeEnable();
        // TLM rings i in [0, searchRange): horizontalRange = searchRange - 1 for exact ring coverage.
        int horizontalRange = WATER_SEARCH_RANGE - 1;
        TaskScanCursor cursor = context.cursor();
        if (cursor == null || !cursor.matches(center, homeMode, horizontalRange, WATER_VERTICAL_RANGE)) {
            cursor = TaskScanCursor.start(center, homeMode, horizontalRange, WATER_VERTICAL_RANGE,
                    context.currentTick(), List.of());
        }

        while (cursor != null && context.consumeBlock()) {
            BlockPos waterPos = cursor.currentPos();
            TaskScanCursor nextCursor = cursor.advance();
            // MaidRideFindWaterTask: restriction first, then the default suitableFishingHook water check.
            if (maid.isWithinRestriction(waterPos)
                    && context.level().getFluidState(waterPos).is(FluidTags.WATER)) {
                context.setCursor(null);
                return new DetectionResult(task.getUid(), Availability.AVAILABLE, context.currentTick(),
                        40, 0, "WATER_TARGET_FOUND", waterPos, null);
            }
            cursor = nextCursor;
        }

        context.setCursor(cursor);
        if (cursor == null) {
            return unavailable(context, task, "FULL_SCAN_NO_WATER");
        }
        return DetectionResult.unknown(task.getUid(), context.currentTick(), "BLOCK_BUDGET_EXHAUSTED");
    }

    /** MaidFindSitTask: idle maids need an unoccupied visible EntityChair inside the home range. */
    private static DetectionResult detectSeat(DetectionContext context, IMaidTask task, EntityMaid maid) {
        Optional<NearestVisibleLivingEntities> visible = maid.getBrain()
                .getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES);
        if (visible.isEmpty()) {
            return DetectionResult.unknown(task.getUid(), context.currentTick(), "MEMORY_NOT_READY");
        }
        List<LivingEntity> candidates = visible.get().find(entity -> entity instanceof EntityChair
                        && entity.distanceToSqr(maid) <= SEAT_SEARCH_RANGE * SEAT_SEARCH_RANGE)
                .filter(entity -> entity.isAlive() && entity.getPassengers().isEmpty()
                        && maid.isWithinRestriction(entity.blockPosition()))
                .sorted(Comparator.comparingDouble(entity -> entity.distanceToSqr(maid)))
                .toList();
        if (candidates.isEmpty()) {
            return unavailable(context, task, "NO_SIT_ENTITY");
        }
        boolean unreachable = false;
        LivingEntity selected = null;
        for (LivingEntity seat : candidates) {
            if (seat.distanceToSqr(maid) < SIT_CLOSE_ENOUGH_SQR) {
                selected = seat;
                break;
            }
            if (!context.consumePathCheck()) {
                return DetectionResult.unknown(task.getUid(), context.currentTick(), "PATH_BUDGET_EXHAUSTED");
            }
            if (maid.canPathReach(seat.blockPosition())) {
                selected = seat;
                break;
            }
            unreachable = true;
        }
        if (selected == null) {
            return unavailable(context, task, unreachable ? "SIT_ALL_UNREACHABLE" : "NO_SIT_ENTITY");
        }
        BlockPos futureSearchCenter = maid.isHomeModeEnable() ? maid.getBrainSearchPos() : selected.blockPosition();
        DetectionResult water = detectWater(context, task, maid, futureSearchCenter);
        if (water.availability() != Availability.AVAILABLE) {
            return water;
        }
        return new DetectionResult(task.getUid(), Availability.AVAILABLE, context.currentTick(),
                40, 0, selected.distanceToSqr(maid) < SIT_CLOSE_ENOUGH_SQR
                ? "SIT_ENTITY_NEARBY" : "SIT_ENTITY_REACHABLE", selected.blockPosition(), selected.getUUID());
    }

    private static DetectionResult unavailable(DetectionContext context, IMaidTask task, String evidence) {
        return new DetectionResult(task.getUid(), Availability.UNAVAILABLE, context.currentTick(),
                40, 0, evidence, null, null);
    }
}
