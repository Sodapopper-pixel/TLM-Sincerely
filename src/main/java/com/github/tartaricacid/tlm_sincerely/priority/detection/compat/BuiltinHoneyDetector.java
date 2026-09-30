package com.github.tartaricacid.tlm_sincerely.priority.detection.compat;

import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionContext;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionResult;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskWorkDetector;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.Comparator;
import java.util.List;

/**
 * Read-only equivalent of TLM TaskHoney / MaidCollectHoneyTask (TLM 1.5.3).
 * Mirrors findBeehive() POI search (PoiTypeTags.BEE_HOME, HONEY_LEVEL >= 5,
 * restrict radius) and the two collection branches: main-hand shears producing
 * 3 honeycombs, or a glass bottle producing 1 honey bottle. Output-space is
 * validated with insertItemStacked(simulate=true) only. Never calls ItemsUtil.
 */
public final class BuiltinHoneyDetector implements TaskWorkDetector {
    public static final ResourceLocation UID = new ResourceLocation("touhou_little_maid", "honey");
    private static final int CLOSE_ENOUGH_DIST = 2;
    private static final double CLOSE_ENOUGH_SQR = CLOSE_ENOUGH_DIST * CLOSE_ENOUGH_DIST;

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
        // 骑乘（椅子/棋盘/船等）时仍参与扫描；只有坐下/睡眠/拴绳才跳过。
        if (maid.getVehicle() == null && !maid.canBrainMoving()) {
            return unavailable(context, task, "MAID_NOT_MOVABLE");
        }
        boolean hasShears = maid.getMainHandItem().canPerformAction(ToolActions.SHEARS_HARVEST);
        // 与 TLM MaidCollectHoneyTask.start 一致：取瓶走含手库存视图，
        // 否则瓶子拿在手上时会被漏报为无瓶。
        boolean hasBottle = hasStack(maid.getAvailableInv(true), Items.GLASS_BOTTLE);
        if (!hasShears && !hasBottle) {
            return unavailable(context, task, "NO_BOTTLE_OR_SHEARS");
        }

        List<BlockPos> hives = findFullHives(context, maid);
        if (hives.isEmpty()) {
            return unavailable(context, task, "NO_FULL_HONEY_HIVE");
        }
        IItemHandler inventory = maid.getAvailableInv(true);
        if (!canCollect(inventory, hasShears, hasBottle)) {
            return unavailable(context, task, "NO_HONEY_OUTPUT_SPACE");
        }
        boolean unreachable = false;
        for (BlockPos hivePos : hives) {
            if (hivePos.distToCenterSqr(maid.position()) < CLOSE_ENOUGH_SQR) {
                return available(context, task, hivePos, "HONEY_HIVE_NEARBY");
            }
            if (!context.consumePathCheck()) {
                return DetectionResult.unknown(task.getUid(), context.currentTick(), "PATH_BUDGET_EXHAUSTED");
            }
            if (maid.canPathReach(hivePos)) {
                return available(context, task, hivePos, "HONEY_HIVE_REACHABLE");
            }
            unreachable = true;
        }
        return unavailable(context, task, unreachable ? "HONEY_HIVE_ALL_UNREACHABLE" : "NO_FULL_HONEY_HIVE");
    }

    private static List<BlockPos> findFullHives(DetectionContext context, EntityMaid maid) {
        return context.level().getPoiManager().getInRange(
                        type -> type.is(PoiTypeTags.BEE_HOME), maid.getBrainSearchPos(),
                        Math.max(0, (int) maid.getRestrictRadius()), PoiManager.Occupancy.ANY)
                .map(record -> record.getPos())
                .filter(pos -> maid.isWithinRestriction(pos) && isFullHive(context, pos))
                .sorted(Comparator.comparingDouble(pos -> pos.distSqr(maid.blockPosition())))
                .toList();
    }

    private static boolean isFullHive(DetectionContext context, BlockPos pos) {
        BlockState state = context.level().getBlockState(pos);
        return state.hasProperty(BeehiveBlock.HONEY_LEVEL)
                && state.getValue(BeehiveBlock.HONEY_LEVEL) >= 5;
    }

    private static boolean canCollect(IItemHandler inventory, boolean hasShears, boolean hasBottle) {
        if (hasShears && fits(inventory, new ItemStack(Items.HONEYCOMB, 3))) {
            return true;
        }
        return hasBottle && fits(inventory, new ItemStack(Items.HONEY_BOTTLE));
    }

    private static boolean fits(IItemHandler inventory, ItemStack product) {
        return ItemHandlerHelper.insertItemStacked(inventory, product, true).isEmpty();
    }

    private static boolean hasStack(IItemHandler inventory, net.minecraft.world.item.Item item) {
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                return true;
            }
        }
        return false;
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
