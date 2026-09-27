package com.github.tartaricacid.tlm_sincerely.priority.detection.compat;

import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionContext;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionResult;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskWorkDetector;
import com.github.tartaricacid.touhoulittlemaid.api.task.IFeedTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Conservative read-only equivalent of TLM TaskFeedOwner / MaidFeedOwnerTask
 * (TLM 1.5.3). Owner must be an alive Player inside the maid's restriction;
 * food is evaluated with the public IFeedTask.isFood/getPriority contract
 * (HIGH or LOW always feedable, LOWEST only while the owner is dying), which
 * matches TLM's selection. Inventory is scanned slot-by-slot; ItemsUtil is
 * never used, so the MaidRequestItemEvent restock path is not simulated
 * (missing food reads as UNAVAILABLE on purpose).
 */
public final class BuiltinFeedOwnerDetector implements TaskWorkDetector {
    public static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "feed");
    private static final int CLOSE_ENOUGH_DIST = 2;

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
        if (!(task instanceof IFeedTask feedTask)) {
            return DetectionResult.unknown(task.getUid(), context.currentTick(), "NOT_FEED_TASK");
        }
        LivingEntity owner = maid.getOwner();
        if (!(owner instanceof Player player) || !owner.isAlive()
                || !maid.isWithinRestriction(owner.blockPosition())) {
            return unavailable(context, task, "OWNER_UNSATISFIED");
        }
        if (!hasFeedableFood(maid, feedTask, player)) {
            return unavailable(context, task, "NO_FEEDABLE_FOOD");
        }
        if (!owner.closerThan(maid, CLOSE_ENOUGH_DIST)) {
            if (!context.consumePathCheck()) {
                return DetectionResult.unknown(task.getUid(), context.currentTick(), "PATH_BUDGET_EXHAUSTED");
            }
            if (!maid.canPathReach(owner)) {
                return unavailable(context, task, "OWNER_UNREACHABLE");
            }
        }
        return new DetectionResult(task.getUid(), Availability.AVAILABLE, context.currentTick(), 20, 0,
                "FEEDABLE_FOOD", null, owner.getUUID());
    }

    private static boolean hasFeedableFood(EntityMaid maid, IFeedTask feedTask, Player player) {
        boolean dying = player.getHealth() / player.getMaxHealth() < 0.5f;
        IItemHandler inventory = maid.getAvailableInv(true);
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!feedTask.isFood(stack, player)) {
                continue;
            }
            IFeedTask.Priority priority = feedTask.getPriority(stack, player);
            if (priority == IFeedTask.Priority.HIGH || priority == IFeedTask.Priority.LOW) {
                return true;
            }
            if (dying && priority == IFeedTask.Priority.LOWEST) {
                return true;
            }
        }
        return false;
    }

    private static DetectionResult unavailable(DetectionContext context, IMaidTask task, String evidence) {
        return new DetectionResult(task.getUid(), Availability.UNAVAILABLE, context.currentTick(), 20, 0,
                evidence, null, null);
    }
}
