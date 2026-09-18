package com.github.tartaricacid.tlm_sincerely.priority.detection.compat;

import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionContext;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionResult;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskWorkDetector;
import com.github.tartaricacid.touhoulittlemaid.api.block.IBoardGameBlock;
import com.github.tartaricacid.touhoulittlemaid.api.block.IBoardGameEntityBlock;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitPoi;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Read-only equivalent of TLM MaidBoardGameTask's JOY_BLOCK target search. */
public final class BuiltinBoardGamesDetector implements TaskWorkDetector {
    public static final ResourceLocation UID = new ResourceLocation("touhou_little_maid", "board_games");
    private static final double CLOSE_ENOUGH_SQR = 4.0D;

    @Override
    public boolean supports(IMaidTask task) {
        return UID.equals(task.getUid());
    }

    @Override
    public DetectionResult detect(DetectionContext context, IMaidTask task) {
        EntityMaid maid = context.maid();
        if (!task.isEnable(maid)) {
            return unavailable(context, task, "TASK_DISABLED");
        }
        int radius = Math.max(0, (int) maid.getRestrictRadius());
        List<BlockPos> candidates = context.level().getPoiManager().getInRange(
                        holder -> holder.value().equals(InitPoi.JOY_BLOCK.get()), maid.getBrainSearchPos(), radius,
                        PoiManager.Occupancy.ANY)
                .map(record -> record.getPos())
                .filter(pos -> isAvailableBoard(context, pos))
                .sorted(Comparator.comparingDouble(pos -> pos.distSqr(maid.blockPosition())))
                .toList();
        if (candidates.isEmpty()) {
            return unavailable(context, task, "NO_JOY_BLOCK");
        }
        boolean unreachable = false;
        for (BlockPos target : candidates) {
            if (target.distToCenterSqr(maid.position()) < CLOSE_ENOUGH_SQR) {
                return available(context, task, target, "JOY_BLOCK_NEARBY");
            }
            if (!context.consumePathCheck()) {
                return DetectionResult.unknown(task.getUid(), context.currentTick(), "PATH_BUDGET_EXHAUSTED");
            }
            if (maid.canPathReach(target)) {
                return available(context, task, target, "JOY_BLOCK_REACHABLE");
            }
            unreachable = true;
        }
        return unavailable(context, task, unreachable ? "JOY_BLOCK_ALL_UNREACHABLE" : "NO_JOY_BLOCK");
    }

    private static boolean isAvailableBoard(DetectionContext context, BlockPos pos) {
        EntityMaid maid = context.maid();
        if (!maid.isWithinRestriction(pos)
                || !(context.level().getBlockState(pos).getBlock() instanceof IBoardGameBlock)) {
            return false;
        }
        BlockEntity blockEntity = context.level().getBlockEntity(pos);
        if (!(blockEntity instanceof IBoardGameEntityBlock board)) {
            return true;
        }
        UUID sitter = board.getSitId();
        if (sitter != null && sitter.equals(maid.getUUID())) {
            // The maid is playing on this board right now: the riding job is
            // still available, so the scheduler keeps her seated instead of
            // switching away and dismounting.
            return true;
        }
        return sitter == null || context.level().getEntity(sitter) == null;
    }

    private static DetectionResult available(DetectionContext context, IMaidTask task, BlockPos target, String evidence) {
        return new DetectionResult(task.getUid(), Availability.AVAILABLE, context.currentTick(), 20, 0,
                evidence, target, null);
    }

    private static DetectionResult unavailable(DetectionContext context, IMaidTask task, String evidence) {
        return new DetectionResult(task.getUid(), Availability.UNAVAILABLE, context.currentTick(), 20, 0,
                evidence, null, null);
    }
}
