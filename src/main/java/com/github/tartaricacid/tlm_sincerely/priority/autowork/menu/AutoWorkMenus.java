package com.github.tartaricacid.tlm_sincerely.priority.autowork.menu;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry owner for the independent auto-work menu types (T-2 A5).
 *
 * <p>Mirrors the TLM {@code InitContainer} pattern: a single
 * {@link DeferredRegister} bound to {@code BuiltInRegistries.MENU}
 * under our mod id. Call {@link #register(IEventBus)} from the mod
 * constructor so the bus is set up before registry events fire.
 */
public final class AutoWorkMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(BuiltInRegistries.MENU, SincerelyExtension.MOD_ID);
    private static boolean registered = false;

    public static final DeferredHolder<MenuType<?>, MenuType<AutoWorkConfigContainer>> AUTO_WORK_CONFIG =
            MENUS.register("auto_work_config", () -> AutoWorkConfigContainer.TYPE);

    private AutoWorkMenus() {
    }

    /** Bind the deferred register to the given mod event bus. Idempotent. */
    public static void register(IEventBus modBus) {
        if (registered) {
            return;
        }
        MENUS.register(modBus);
        registered = true;
    }
}
