package com.github.tartaricacid.tlm_sincerely.priority.detection;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
import com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IRangedAttackTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.item.ItemHakureiGohei;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Weapon-aware attack family detector.
 *
 * <p>Every weapon check accepts a matching main-hand <b>or</b> backpack stack;
 * {@link MaidHardToolService} moves it to the main hand right before the
 * switch. Melee {@code touhou_little_maid:attack} never treats ranged weapons
 * as usable even though TLM's {@code isWeapon} only looks for ATTACK_DAMAGE;
 * third-party {@link IAttackTask}s keep their own {@code isWeapon} contract.
 */
public final class AttackTaskWorkDetector implements TaskWorkDetector {
    public static final ResourceLocation UID_ATTACK = ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "attack");
    public static final ResourceLocation UID_RANGED = ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "ranged_attack");
    public static final ResourceLocation UID_CROSSBOW = ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "crossbow_attack");
    public static final ResourceLocation UID_TRIDENT = ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "trident_attack");
    public static final ResourceLocation UID_DANMAKU = ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "danmaku_attack");

    /** 近战武器：主手 ATTACK_DAMAGE，且排除弓/弩/三叉戟/御币。 */
    public static final HardToolRequirement MELEE_WEAPON = new HardToolRequirement("attack_weapon",
            stack -> hasMainHandAttackDamage(stack)
                    && !(stack.getItem() instanceof BowItem)
                    && !(stack.getItem() instanceof CrossbowItem)
                    && !(stack.getItem() instanceof TridentItem)
                    && !ItemHakureiGohei.isGohei(stack));

    /**
     * 1.21.1 removed the per-slot {@code getAttributeModifiers} map; the slot query
     * now goes through {@link ItemStack#forEachModifier(EquipmentSlot, BiConsumer)},
     * which walks item-intrinsic modifiers, the ATTRIBUTE_MODIFIERS component and
     * enchantments for that slot — a conservative superset of the old
     * {@code getAttributeModifiers(MAINHAND).containsKey(ATTACK_DAMAGE)} check.
     */
    public static boolean hasMainHandAttackDamage(ItemStack stack) {
        boolean[] found = {false};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            // is(Holder) is deprecated in 1.21.1; key comparison is the supported form.
            if (attribute.is(Attributes.ATTACK_DAMAGE.getKey())) {
                found[0] = true;
            }
        });
        return found[0];
    }

    public static final HardToolRequirement BOW_WEAPON = new HardToolRequirement(
            "ranged_weapon", stack -> stack.getItem() instanceof BowItem);
    public static final HardToolRequirement CROSSBOW_WEAPON = new HardToolRequirement(
            "crossbow_weapon", stack -> stack.getItem() instanceof CrossbowItem);
    public static final HardToolRequirement TRIDENT_WEAPON = new HardToolRequirement(
            "trident_weapon", stack -> stack.getItem() instanceof TridentItem);
    public static final HardToolRequirement DANMAKU_WEAPON = new HardToolRequirement(
            "danmaku_weapon", ItemHakureiGohei::isGohei);

    @Override
    public boolean supports(IMaidTask task) {
        return task instanceof IAttackTask;
    }

    @Override
    public DetectionResult detect(DetectionContext context, IMaidTask task) {
        if (!(task instanceof IAttackTask attackTask)) {
            return DetectionResult.unknown(task.getUid(), context.currentTick(), "NOT_ATTACK_TASK");
        }
        EntityMaid maid = context.maid();
        if (!task.isEnable(maid)) {
            return new DetectionResult(task.getUid(), Availability.UNAVAILABLE, context.currentTick(),
                    20, 0, "TASK_DISABLED", null, null);
        }
        if (!hasRequiredWeapon(maid, task)) {
            return new DetectionResult(task.getUid(), Availability.UNAVAILABLE, context.currentTick(),
                    20, 0, "ATTACK_WEAPON_REQUIRED", null, null);
        }
        return findTarget(context, maid, task)
                .<DetectionResult>map(target -> available(context, task, target))
                .orElseGet(() -> new DetectionResult(task.getUid(), Availability.UNAVAILABLE, context.currentTick(),
                        20, 0, "NO_ATTACK_TARGET", null, null));
    }

    @Override
    public int minIntervalTicks() {
        return PriorityConfig.EXPERIMENTAL_ATTACK_PREEMPT.get() ? 5 : 10;
    }

    /**
     * Mirrors the range the maid will actually accept once she is in this
     * attack task, instead of reading the current task's Brain memory.
     *
     * <p>TLM drops targets that fall outside the task's own reach
     * ({@code TaskAttack#farAway} measures owner-to-target in follow mode,
     * {@code TaskBowAttack#farAway} measures maid-to-target up to
     * {@code searchRadius}). Detecting with a wider range would switch the
     * maid into the attack task and leave her standing idle.
     */
    private static java.util.Optional<? extends LivingEntity> findTarget(
            DetectionContext context, EntityMaid maid, IMaidTask task) {
        if (!(task instanceof IAttackTask attackTask)) {
            return java.util.Optional.empty();
        }
        AABB search = task.searchDimension(maid);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : context.level().getEntitiesOfClass(LivingEntity.class, search,
                entity -> entity.isAlive() && entity != maid)) {
            if (!attackTask.canAttack(maid, candidate)
                    || !withinReactionRange(maid, task, candidate)
                    || !canSeeTarget(maid, task, candidate)) {
                continue;
            }
            double distance = maid.distanceToSqr(candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return java.util.Optional.ofNullable(best);
    }

    /** Same reach as the main mod's attack task for this task family. */
    private static boolean withinReactionRange(EntityMaid maid, IMaidTask task, LivingEntity target) {
        float radius = task.searchRadius(maid);
        if (task instanceof IRangedAttackTask) {
            return maid.distanceTo(target) <= radius;
        }
        // TaskAttack.farAway: follow mode measures from the owner, home mode from the maid.
        LivingEntity center = !maid.isHomeModeEnable() && maid.getOwner() != null ? maid.getOwner() : maid;
        return center.distanceTo(target) <= radius;
    }

    /** Line of sight, using the ranged task's own visibility rule when it has one. */
    private static boolean canSeeTarget(EntityMaid maid, IMaidTask task, LivingEntity target) {
        if (task instanceof IRangedAttackTask rangedTask) {
            return rangedTask.canSee(maid, target);
        }
        return BehaviorUtils.canSee(maid, target);
    }

    /** Main-hand or backpack weapon check per task family. */
    public static boolean hasRequiredWeapon(EntityMaid maid, IMaidTask task) {
        if (UID_RANGED.equals(task.getUid())) {
            return MaidHardToolService.hasAny(maid, BOW_WEAPON) && hasArrow(maid);
        }
        if (UID_ATTACK.equals(task.getUid())) {
            return MaidHardToolService.hasAny(maid, MELEE_WEAPON);
        }
        if (UID_CROSSBOW.equals(task.getUid())) {
            return MaidHardToolService.hasAny(maid, CROSSBOW_WEAPON);
        }
        if (UID_TRIDENT.equals(task.getUid())) {
            return MaidHardToolService.hasAny(maid, TRIDENT_WEAPON);
        }
        if (UID_DANMAKU.equals(task.getUid())) {
            return MaidHardToolService.hasAny(maid, DANMAKU_WEAPON);
        }
        // Third-party attack task: keep its own isWeapon contract.
        if (task instanceof IAttackTask attackTask) {
            return hasStack(maid, stack -> attackTask.isWeapon(maid, stack));
        }
        return true;
    }

    private static boolean hasStack(EntityMaid maid, Predicate<ItemStack> matcher) {
        if (!maid.getMainHandItem().isEmpty() && matcher.test(maid.getMainHandItem())) {
            return true;
        }
        IItemHandler backpack = maid.getAvailableBackpackInv();
        for (int slot = 0; slot < backpack.getSlots(); slot++) {
            ItemStack stack = backpack.getStackInSlot(slot);
            if (!stack.isEmpty() && matcher.test(stack)) {
                return true;
            }
        }
        return false;
    }

    /** Arrows may live anywhere in the maid's available inventory (hands + backpack). */
    private static boolean hasArrow(EntityMaid maid) {
        ItemStack bow = findStack(maid, BOW_WEAPON);
        if (bow.isEmpty() || !(bow.getItem() instanceof BowItem bowItem)) {
            return false;
        }
        Predicate<ItemStack> projectiles = bowItem.getAllSupportedProjectiles();
        IItemHandler inventory = maid.getAvailableInv(true);
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && projectiles.test(stack)) {
                return true;
            }
        }
        return false;
    }

    /** Returns the first matching stack (main hand first), ignoring empty stacks. */
    public static ItemStack findStack(EntityMaid maid, HardToolRequirement requirement) {
        if (requirement.matches(maid.getMainHandItem())) {
            return maid.getMainHandItem();
        }
        IItemHandler backpack = maid.getAvailableBackpackInv();
        for (int slot = 0; slot < backpack.getSlots(); slot++) {
            ItemStack stack = backpack.getStackInSlot(slot);
            if (requirement.matches(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private DetectionResult available(DetectionContext context, IMaidTask task, LivingEntity target) {
        return new DetectionResult(task.getUid(), Availability.AVAILABLE, context.currentTick(),
                20, 0, "ATTACK_TARGET", null, target.getUUID());
    }
}
