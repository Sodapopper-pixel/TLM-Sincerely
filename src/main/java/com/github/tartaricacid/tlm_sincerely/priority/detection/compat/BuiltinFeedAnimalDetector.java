package com.github.tartaricacid.tlm_sincerely.priority.detection.compat;

import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionContext;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionResult;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskWorkDetector;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.MaidConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read-only equivalent of TLM TaskFeedAnimal's benign feeding branch
 * (MaidFeedAnimalTask, TLM 1.5.3). Only the breeding/feeding semantics are
 * detected: visible Animal count below FEED_ANIMAL_MAX_NUMBER, an adult
 * (getAge() == 0) animal that canFallInLove(), and matching food in the
 * inventory. The over-limit attack branch (>= MAX - 2, MaidMeleeAttack) is
 * deliberately never reported as AVAILABLE. Food lookup walks inventory slots
 * directly; ItemsUtil / MaidRequestItemEvent is never touched.
 */
public final class BuiltinFeedAnimalDetector implements TaskWorkDetector {
    public static final ResourceLocation UID = new ResourceLocation("touhou_little_maid", "feed_animal");

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
        Optional<NearestVisibleLivingEntities> visibleMemory = visibleEntities(maid);
        if (visibleMemory.isEmpty()) {
            return DetectionResult.unknown(task.getUid(), context.currentTick(), "MEMORY_NOT_READY");
        }
        NearestVisibleLivingEntities visible = visibleMemory.get();
        long animalCount = visible.find(entity -> maid.isWithinRestriction(entity.blockPosition()))
                .filter(Entity::isAlive)
                .filter(entity -> entity instanceof Animal)
                .count();
        if (animalCount >= MaidConfig.FEED_ANIMAL_MAX_NUMBER.get()) {
            return unavailable(context, task, "ANIMAL_COUNT_MAX");
        }

        List<ItemStack> foodStacks = collectFoodStacks(maid);
        boolean unreachable = false;
        for (LivingEntity candidate : visible.find(entity -> maid.isWithinRestriction(entity.blockPosition()))
                .filter(Entity::isAlive)
                .filter(entity -> entity instanceof Animal)
                .filter(entity -> ((Animal) entity).getAge() == 0)
                .filter(entity -> ((Animal) entity).canFallInLove())
                .filter(entity -> hasMatchingFood(foodStacks, (Animal) entity))
                .toList()) {
            if (!context.consumePathCheck()) {
                return DetectionResult.unknown(task.getUid(), context.currentTick(), "PATH_BUDGET_EXHAUSTED");
            }
            if (maid.canPathReach(candidate)) {
                return available(context, task, candidate, "FEEDABLE_ANIMAL");
            }
            unreachable = true;
        }
        return unavailable(context, task, unreachable ? "ANIMAL_ALL_UNREACHABLE" : "NO_FEEDABLE_ANIMAL");
    }

    private static Optional<NearestVisibleLivingEntities> visibleEntities(EntityMaid maid) {
        return maid.getBrain().getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES);
    }

    private static List<ItemStack> collectFoodStacks(EntityMaid maid) {
        IItemHandler inventory = maid.getAvailableInv(false);
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                stacks.add(stack);
            }
        }
        return stacks;
    }

    private static boolean hasMatchingFood(List<ItemStack> stacks, Animal animal) {
        for (ItemStack stack : stacks) {
            if (animal.isFood(stack)) {
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
