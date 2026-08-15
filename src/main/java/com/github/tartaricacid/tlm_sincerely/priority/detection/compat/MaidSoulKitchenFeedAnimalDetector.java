package com.github.tartaricacid.tlm_sincerely.priority.detection.compat;

import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionContext;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionResult;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskWorkDetector;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.items.IItemHandler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * UID detector for {@code maidsoulkitchen:feed_animal_t}, replacing the wrong
 * {@code IAttackTask} fallback (a maid must not be switched in because a
 * hostile target is nearby).
 *
 * <p>The semantics mirror MaidSoulKitchen 0.3.0.9 {@code TaskFeedAnimalT} /
 * {@code MaidFeedAnimalTaskT}:
 * <ul>
 *   <li>animals are grouped by {@link EntityType} (from the maid brain's
 *       {@code NEAREST_VISIBLE_LIVING_ENTITIES} memory, exactly where the
 *       addon reads them);</li>
 *   <li>a breeding group of {@code [3, max - 3]} is work when it contains an
 *       adult ({@code !isBaby && canFallInLove}) animal the maid can reach
 *       and whose food exists in the maid inventory;</li>
 *   <li>the optional cleanup branch (group {@code >= max - 2}) only reports
 *       {@code AVAILABLE} when the main hand holds an attack weapon, food
 *       exists and an adult animal is reachable.</li>
 * </ul>
 *
 * <p>No MaidSoulKitchen class is referenced at compile time. The only
 * addon-specific value is {@code TaskConfig.FEED_SINGLE_ANIMAL_MAX_NUMBER}
 * which is read through a single cached reflection point and falls back to
 * 20. The detector never mutates inventories or calls the addon state
 * machine.
 */
public final class MaidSoulKitchenFeedAnimalDetector implements TaskWorkDetector {
    public static final ResourceLocation UID = new ResourceLocation("maidsoulkitchen", "feed_animal_t");
    /** Lower bound of a breeding group, matching the player-facing "3..max-3" rule. */
    private static final int BREEDING_GROUP_MIN = 3;
    private static final int CLEANUP_GROUP_OFFSET = 2;

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
        int maxAnimalCount = FeedConfig.MAX_ANIMAL_COUNT;
        Optional<List<LivingEntity>> nearbyMemory = maid.getBrain()
                .getMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES);
        if (nearbyMemory.isEmpty()) {
            return DetectionResult.unknown(task.getUid(), context.currentTick(), "MEMORY_NOT_READY");
        }
        Map<EntityType<?>, List<Animal>> breedingGroups = groupAnimals(nearbyMemory.get(), maid);
        for (List<Animal> group : breedingGroups.values()) {
            int size = group.size();
            if (size >= BREEDING_GROUP_MIN && size <= maxAnimalCount - BREEDING_GROUP_MIN) {
                DetectionResult breeding = scanGroup(context, task, group, true, "FEED_BREEDING_GROUP");
                if (breeding != null) {
                    return breeding;
                }
            }
        }
        if (hasAssaultWeapon(maid)) {
            Optional<NearestVisibleLivingEntities> visibleMemory = maid.getBrain()
                    .getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES);
            if (visibleMemory.isEmpty()) {
                return DetectionResult.unknown(task.getUid(), context.currentTick(), "MEMORY_NOT_READY");
            }
            Map<EntityType<?>, List<Animal>> cleanupGroups =
                    groupAnimals(visibleMemory.get().findAll(entity -> true), maid);
            for (List<Animal> group : cleanupGroups.values()) {
                if (group.size() < maxAnimalCount - CLEANUP_GROUP_OFFSET) {
                    continue;
                }
                DetectionResult cleanup = scanGroup(context, task, group, false, "FEED_CLEANUP_GROUP");
                if (cleanup != null) {
                    return cleanup;
                }
            }
        }
        return unavailable(context, task, "NO_BREEDABLE_GROUP");
    }

    private static Map<EntityType<?>, List<Animal>> groupAnimals(Iterable<? extends LivingEntity> entities,
                                                                 EntityMaid maid) {
        Map<EntityType<?>, List<Animal>> groups = new LinkedHashMap<>();
        for (LivingEntity entity : entities) {
            if (!(entity instanceof Animal animal) || !animal.isAlive()
                    || !maid.isWithinRestriction(animal.blockPosition())) {
                continue;
            }
            groups.computeIfAbsent(animal.getType(), type -> new ArrayList<>()).add(animal);
        }
        return groups;
    }

    /**
     * Picks the first feedable animal of a group. {@code breeding} decides the
     * food inventory view: {@code true} mirrors {@code MaidFeedAnimalTaskT}
     * (available inventory including main hand), {@code false} mirrors
     * {@code TaskFeedAnimalT#findFirstValidAttackTarget} (backpack without
     * main hand).
     */
    private static DetectionResult scanGroup(DetectionContext context, IMaidTask task,
                                             List<Animal> group, boolean breeding, String evidence) {
        EntityMaid maid = context.maid();
        for (Animal animal : group) {
            if (animal.isBaby() || (breeding && (animal.getAge() != 0 || !animal.canFallInLove()))) {
                continue;
            }
            // Food matching is per-animal (animals of one type share food, but a
            // stack accepted by one candidate may not be accepted by another type).
            if (!hasFood(maid, animal, breeding)) {
                continue;
            }
            if (!context.consumePathCheck()) {
                return DetectionResult.unknown(task.getUid(), context.currentTick(), "PATH_BUDGET_EXHAUSTED");
            }
            if (maid.canPathReach(animal)) {
                return new DetectionResult(task.getUid(), Availability.AVAILABLE, context.currentTick(),
                        40, 0, evidence, null, animal.getUUID());
            }
        }
        return null;
    }

    /** Hand-sweeps the maid inventory for a stack the animal accepts as food. */
    private static boolean hasFood(EntityMaid maid, Animal animal, boolean includeMainHand) {
        IItemHandler inventory = maid.getAvailableInv(includeMainHand);
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && animal.isFood(stack)) {
                return true;
            }
        }
        return false;
    }

    /** Mirrors {@code TaskFeedAnimalT#hasAssaultWeapon}: main-hand item must carry attack damage. */
    private static boolean hasAssaultWeapon(EntityMaid maid) {
        ItemStack mainHand = maid.getMainHandItem();
        return !mainHand.isEmpty()
                && mainHand.getAttributeModifiers(EquipmentSlot.MAINHAND).containsKey(Attributes.ATTACK_DAMAGE);
    }

    private static DetectionResult unavailable(DetectionContext context, IMaidTask task, String evidence) {
        return new DetectionResult(task.getUid(), Availability.UNAVAILABLE, context.currentTick(),
                40, 0, evidence, null, null);
    }

    /**
     * Single cached reflection point for the addon config. Any failure falls
     * back to the 20 default so the detector stays functional across addon
     * versions.
     */
    private static final class FeedConfig {
        private static final String TASK_CONFIG_CLASS =
                "com.github.wallev.maidsoulkitchen.config.subconfig.TaskConfig";
        private static final String MAX_NUMBER_FIELD = "FEED_SINGLE_ANIMAL_MAX_NUMBER";
        private static final int FALLBACK_MAX = 20;
        static final int MAX_ANIMAL_COUNT = readMaxAnimalCount();

        private FeedConfig() {
        }

        private static int readMaxAnimalCount() {
            try {
                Class<?> configClass = Class.forName(TASK_CONFIG_CLASS);
                Field field = configClass.getField(MAX_NUMBER_FIELD);
                Object configValue = field.get(null);
                if (configValue instanceof ForgeConfigSpec.ConfigValue<?> typed) {
                    Object raw = typed.get();
                    if (raw instanceof Integer count && count > 0) {
                        return count;
                    }
                }
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
                // Addon absent or API changed: keep the default.
            }
            return FALLBACK_MAX;
        }
    }
}
