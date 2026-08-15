package com.github.tartaricacid.tlm_sincerely.priority.detection.compat;

import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionContext;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionResult;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskWorkDetector;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraftforge.common.IForgeShearable;

/** Mirrors TLM TaskShears' public target requirements without changing world state. */
public final class BuiltinShearsDetector implements TaskWorkDetector {
    public static final ResourceLocation UID = new ResourceLocation("touhou_little_maid", "shears");
    private static final double TARGET_RANGE_SQR = 4.0D;

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
        ItemStack tool = maid.getMainHandItem();
        if (!(tool.getItem() instanceof ShearsItem)) {
            return unavailable(context, task, "SHEARS_REQUIRED");
        }
        for (LivingEntity entity : context.level().getEntitiesOfClass(LivingEntity.class,
                maid.getBoundingBox().inflate(2.0D))) {
            if (!(entity instanceof IForgeShearable shearable) || !entity.isAlive()
                    || entity.distanceToSqr(maid) >= TARGET_RANGE_SQR
                    || !maid.isWithinRestriction(entity.blockPosition())
                    || !shearable.isShearable(tool, context.level(), entity.blockPosition())) {
                continue;
            }
            return new DetectionResult(task.getUid(), Availability.AVAILABLE, context.currentTick(), 20, 0,
                    "SHEARABLE_ENTITY", null, entity.getUUID());
        }
        return unavailable(context, task, "NO_SHEARABLE_ENTITY");
    }

    private static DetectionResult unavailable(DetectionContext context, IMaidTask task, String evidence) {
        return new DetectionResult(task.getUid(), Availability.UNAVAILABLE, context.currentTick(), 20, 0,
                evidence, null, null);
    }
}
