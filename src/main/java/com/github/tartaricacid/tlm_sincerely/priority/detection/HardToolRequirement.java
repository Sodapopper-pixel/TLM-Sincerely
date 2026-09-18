package com.github.tartaricacid.tlm_sincerely.priority.detection;

import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/** A task tool that must exist and be equipped in the maid's main hand. */
public record HardToolRequirement(String id, Predicate<ItemStack> matcher) {
    public boolean matches(ItemStack stack) {
        return !stack.isEmpty() && matcher.test(stack);
    }
}
