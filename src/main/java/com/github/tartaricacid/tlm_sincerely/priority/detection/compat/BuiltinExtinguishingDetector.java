package com.github.tartaricacid.tlm_sincerely.priority.detection.compat;

import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionContext;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionResult;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskWorkDetector;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import java.util.List;

/**
 * Read-only equivalent of TLM TaskExtinguishing / MaidExtinguishingTask
 * (TLM 1.5.3). Mirrors the three fire branches in TLM order: owner on fire
 * (with home restriction), maid itself on fire, or a burning TamableAnimal
 * inside the maid's AABB inflated by (2, 1, 2). The extinguisher must already
 * be in the main hand because automatic setTask does not run the tool-equip
 * callback. No path checks:
 * the agent spawns at the maid's own position. Never calls ItemsUtil.
 */
public final class BuiltinExtinguishingDetector implements TaskWorkDetector {
    public static final ResourceLocation UID = new ResourceLocation("touhou_little_maid", "extinguishing");

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
        if (maid.getMainHandItem().getItem() != InitItems.EXTINGUISHER.get()) {
            return unavailable(context, task, "EXTINGUISHER_REQUIRED");
        }

        LivingEntity owner = maid.getOwner();
        if (owner instanceof Player && owner.isAlive() && owner.isOnFire()
                && maid.isWithinRestriction(owner.blockPosition())) {
            if (owner.closerThan(maid, 2.0D)) {
                return available(context, task, owner, "OWNER_ON_FIRE");
            }
            if (!context.consumePathCheck()) {
                return DetectionResult.unknown(task.getUid(), context.currentTick(), "PATH_BUDGET_EXHAUSTED");
            }
            if (maid.canPathReach(owner)) {
                return available(context, task, owner, "OWNER_ON_FIRE");
            }
            return unavailable(context, task, "OWNER_FIRE_UNREACHABLE");
        }
        if (maid.isOnFire()) {
            return available(context, task, maid, "SELF_ON_FIRE");
        }
        List<TamableAnimal> burningTamed = context.level().getEntitiesOfClass(TamableAnimal.class,
                maid.getBoundingBox().inflate(2.0D, 1.0D, 2.0D), Entity::isOnFire);
        if (!burningTamed.isEmpty()) {
            return available(context, task, burningTamed.get(0), "TAMED_ENTITY_ON_FIRE");
        }
        return unavailable(context, task, "NO_FIRE_TARGET");
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
