package com.github.tartaricacid.tlm_sincerely.client.gui.priority;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class PriorityRegistry {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, SincerelyExtension.MOD_ID);

    public static final RegistryObject<MenuType<PriorityContainer>> PRIORITY_CONTAINER =
            MENU_TYPES.register("priority_container", PriorityContainer::createType);
}
