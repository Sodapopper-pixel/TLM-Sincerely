package com.github.tartaricacid.tlm_sincerely.priority.detection.compat;

import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionContext;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionResult;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskScanCursor;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskWorkDetector;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.IItemHandler;

import java.util.List;

/**
 * Read-only equivalent of TLM 1.5.3 TaskTorch + MaidTorchMoveTask + MaidTorchPlaceTask.
 * Scans the same dark replaceable positions and never writes to the world.
 *
 * <p>Vertical alignment: {@link TaskScanCursor#verticalOffset()} yields the same
 * per-layer offsets as TLM's {@code y - 1} loop (relative to the scan centre):
 * {@code -1, 0, -2, 1, -3, ...}. With {@link #TORCH_VERTICAL_RANGE} = 2 the cursor
 * covers exactly TLM's five layers ({@code y} in 0, 1, -1, 2, -2), so no centre
 * translation or layer filtering is needed. Horizontal rings match TLM's
 * {@code i < searchRange} loop by using {@code restrictRadius - 1}.
 */
public final class BuiltinTorchDetector implements TaskWorkDetector {
    public static final ResourceLocation UID = new ResourceLocation("touhou_little_maid", "torch");
    private static final int LOW_BRIGHTNESS = 9;
    private static final int TORCH_VERTICAL_RANGE = 2;

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
        if (!hasTorch(maid)) {
            context.setCursor(null);
            return unavailable(context, task, "NO_TORCH");
        }
        boolean homeMode = maid.isHomeModeEnable();
        BlockPos center = homeMode ? maid.getRestrictCenter() : maid.blockPosition();
        // TLM MaidMoveToBlockTask: rings i in [0, restrictRadius), so the last ring is radius - 1.
        int horizontalRange = Math.max(0, (int) maid.getRestrictRadius() - 1);
        TaskScanCursor cursor = context.cursor();
        if (cursor == null || !cursor.matches(center, homeMode, horizontalRange, TORCH_VERTICAL_RANGE)) {
            cursor = TaskScanCursor.start(center, homeMode, horizontalRange, TORCH_VERTICAL_RANGE,
                    context.currentTick(), List.of());
        }
        int unreachableCandidates = cursor.unreachableCandidates();
        ServerLevel level = context.level();

        while (cursor != null && context.consumeBlock()) {
            BlockPos pos = cursor.currentPos();
            TaskScanCursor nextCursor = cursor.advance();

            // MaidMoveToBlockTask.searchForDestination: restriction checked before the position test.
            if (!maid.isWithinRestriction(pos)) {
                cursor = nextCursor;
                continue;
            }
            // MaidTorchMoveTask.shouldMoveTo: dark, replaceable, survivable, non-liquid spot above.
            BlockPos posUp = pos.above();
            if (level.getMaxLocalRawBrightness(posUp) >= LOW_BRIGHTNESS || !maid.canPlaceBlock(posUp)) {
                cursor = nextCursor;
                continue;
            }
            BlockState stateUp = level.getBlockState(posUp);
            if (!Blocks.TORCH.canSurvive(stateUp, level, posUp) || stateUp.liquid()) {
                cursor = nextCursor;
                continue;
            }
            // MaidMoveToBlockTask.checkOwnerPos: non-home maids stay within 8 blocks of their owner.
            if (!isNearOwner(maid, pos)) {
                cursor = nextCursor;
                continue;
            }
            if (!context.consumePathCheck()) {
                context.setCursor(nextCursor);
                return DetectionResult.unknown(task.getUid(), context.currentTick(), "PATH_BUDGET_EXHAUSTED");
            }
            if (maid.canPathReach(pos)) {
                context.setCursor(null);
                return new DetectionResult(task.getUid(), Availability.AVAILABLE, context.currentTick(),
                        40, 0, "TORCH_PLACEABLE", pos, null);
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

    /** TaskTorch.hasTorch: plain torches only, backpacks searched before hands (handsFirst = false). */
    private static boolean hasTorch(EntityMaid maid) {
        IItemHandler inventory = maid.getAvailableInv(false);
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (inventory.getStackInSlot(slot).getItem() == Items.TORCH) {
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
