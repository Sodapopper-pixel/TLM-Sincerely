package com.github.tartaricacid.tlm_sincerely.priority.detection.compat;

import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionContext;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionResult;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskWorkDetector;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.IItemHandler;

import java.util.List;
import java.util.Optional;

/**
 * Read-only equivalent of TLM TaskMilk / MaidMilkTask (TLM 1.5.3).
 * Requires a bucket plus a free inventory slot, then scans the same
 * NEAREST_VISIBLE_LIVING_ENTITIES memory (adult, alive Cow within the maid's
 * restriction) TLM reads. Path checks go through consumePathCheck(). Never
 * calls ItemsUtil (would trigger MaidRequestItemEvent).
 */
public final class BuiltinMilkDetector implements TaskWorkDetector {
    public static final ResourceLocation UID = new ResourceLocation("touhou_little_maid", "milk");
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
        IItemHandler inventory = maid.getAvailableInv(true);
        if (!hasStack(inventory, Items.BUCKET)) {
            return unavailable(context, task, "NO_BUCKET");
        }
        if (!hasEmptySlot(inventory)) {
            return unavailable(context, task, "NO_INV_SPACE");
        }

        Optional<NearestVisibleLivingEntities> visible = visibleEntities(maid);
        if (visible.isEmpty()) {
            return DetectionResult.unknown(task.getUid(), context.currentTick(), "MEMORY_NOT_READY");
        }
        List<LivingEntity> cows = visible.get()
                .find(entity -> maid.isWithinRestriction(entity.blockPosition()))
                .filter(LivingEntity::isAlive)
                .filter(entity -> entity instanceof Cow)
                .filter(entity -> !entity.isBaby())
                .toList();
        if (cows.isEmpty()) {
            return unavailable(context, task, "NO_MILKABLE_COW");
        }
        boolean unreachable = false;
        for (LivingEntity cow : cows) {
            if (cow.closerThan(maid, CLOSE_ENOUGH_DIST)) {
                return available(context, task, cow, "COW_NEARBY");
            }
            if (!context.consumePathCheck()) {
                return DetectionResult.unknown(task.getUid(), context.currentTick(), "PATH_BUDGET_EXHAUSTED");
            }
            if (maid.canPathReach(cow)) {
                return available(context, task, cow, "COW_REACHABLE");
            }
            unreachable = true;
        }
        return unavailable(context, task, unreachable ? "COW_ALL_UNREACHABLE" : "NO_MILKABLE_COW");
    }

    private static Optional<NearestVisibleLivingEntities> visibleEntities(EntityMaid maid) {
        return maid.getBrain().getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES);
    }

    private static boolean hasStack(IItemHandler inventory, net.minecraft.world.item.Item item) {
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEmptySlot(IItemHandler inventory) {
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (inventory.getStackInSlot(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static DetectionResult available(DetectionContext context, IMaidTask task, LivingEntity target, String evidence) {
        return new DetectionResult(task.getUid(), Availability.AVAILABLE, context.currentTick(), 20, 0,
                evidence, null, target.getUUID());
    }

    private static DetectionResult unavailable(DetectionContext context, IMaidTask task, String evidence) {
        return new DetectionResult(task.getUid(), Availability.UNAVAILABLE, context.currentTick(), 20, 0,
                evidence, null, null);
    }
}
