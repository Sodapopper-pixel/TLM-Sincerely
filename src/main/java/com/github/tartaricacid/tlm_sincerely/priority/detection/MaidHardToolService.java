package com.github.tartaricacid.tlm_sincerely.priority.detection;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Read-only tool checks for detectors and safe equipping before task switches. */
public final class MaidHardToolService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MaidHardToolService.class);
    private static final Map<ResourceLocation, HardToolRequirement> TASK_REQUIREMENTS = new ConcurrentHashMap<>();

    private MaidHardToolService() {
    }

    public enum EquipResult {
        ALREADY_EQUIPPED,
        SUCCESS,
        MISSING,
        NO_REQUIREMENT
    }

    public record EquipTransaction(EquipResult result, Runnable rollback) {
        private static EquipTransaction unchanged(EquipResult result) {
            return new EquipTransaction(result, () -> { });
        }
    }

    /**
     * 注册任务 UID 到工具需求的映射。重复注册以最后一次为准（仅记录 debug）。
     */
    public static void register(ResourceLocation taskUid, HardToolRequirement requirement) {
        HardToolRequirement previous = TASK_REQUIREMENTS.put(taskUid, requirement);
        if (previous != null) {
            LOGGER.debug("[HardTool] re-register requirement for {}: {} -> {}", taskUid, previous.id(), requirement.id());
        } else {
            LOGGER.debug("[HardTool] register requirement {} for {}", requirement.id(), taskUid);
        }
    }

    public static HardToolRequirement getRequirement(ResourceLocation taskUid) {
        return TASK_REQUIREMENTS.get(taskUid);
    }

    public static boolean hasAny(EntityMaid maid, HardToolRequirement requirement) {
        if (requirement.matches(maid.getMainHandItem())) {
            return true;
        }
        IItemHandler backpack = maid.getAvailableBackpackInv();
        for (int slot = 0; slot < backpack.getSlots(); slot++) {
            if (requirement.matches(backpack.getStackInSlot(slot))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Read-only query returning a matching tool copy, preferring the main hand;
     * 找不到返回 {@link ItemStack#EMPTY}。返回副本，调用方修改不影响女仆库存。
     */
    public static ItemStack findBest(EntityMaid maid, HardToolRequirement requirement) {
        ItemStack mainHand = maid.getMainHandItem();
        if (requirement.matches(mainHand)) {
            return mainHand.copy();
        }
        IItemHandler backpack = maid.getAvailableBackpackInv();
        for (int slot = 0; slot < backpack.getSlots(); slot++) {
            ItemStack stack = backpack.getStackInSlot(slot);
            if (requirement.matches(stack)) {
                return stack.copy();
            }
        }
        return ItemStack.EMPTY;
    }

    public static EquipTransaction equipTransactional(EntityMaid maid, HardToolRequirement requirement) {
        if (requirement.matches(maid.getMainHandItem())) {
            return EquipTransaction.unchanged(EquipResult.ALREADY_EQUIPPED);
        }
        CombinedInvWrapper backpack = maid.getAvailableBackpackInv();
        for (int slot = 0; slot < backpack.getSlots(); slot++) {
            ItemStack stack = backpack.getStackInSlot(slot);
            if (!requirement.matches(stack)) {
                continue;
            }
            ItemStack originalSlot = stack.copy();
            ItemStack previousMainHand = maid.getMainHandItem().copy();
            ItemStack tool = backpack.extractItem(slot, originalSlot.getCount(), false);
            if (tool.isEmpty()) {
                continue;
            }
            try {
                backpack.setStackInSlot(slot, previousMainHand);
                maid.setItemInHand(InteractionHand.MAIN_HAND, tool);
                int transactionSlot = slot;
                return new EquipTransaction(EquipResult.SUCCESS, () -> {
                    try {
                        maid.setItemInHand(InteractionHand.MAIN_HAND, previousMainHand.copy());
                        backpack.setStackInSlot(transactionSlot, originalSlot.copy());
                    } catch (RuntimeException rollbackFailure) {
                        LOGGER.error("[HardTool] transaction rollback failed for maid={}", maid.getUUID(), rollbackFailure);
                    }
                });
            } catch (Exception e) {
                maid.setItemInHand(InteractionHand.MAIN_HAND, previousMainHand);
                backpack.setStackInSlot(slot, originalSlot);
                LOGGER.error("[HardTool] equip failed for maid={}, rolled back", maid.getUUID(), e);
                return EquipTransaction.unchanged(EquipResult.MISSING);
            }
        }
        return EquipTransaction.unchanged(EquipResult.MISSING);
    }

    public static EquipTransaction equipTaskRequirementTransactional(EntityMaid maid, ResourceLocation taskUid) {
        HardToolRequirement requirement = TASK_REQUIREMENTS.get(taskUid);
        if (requirement == null) {
            return EquipTransaction.unchanged(EquipResult.NO_REQUIREMENT);
        }
        return equipTransactional(maid, requirement);
    }
}
