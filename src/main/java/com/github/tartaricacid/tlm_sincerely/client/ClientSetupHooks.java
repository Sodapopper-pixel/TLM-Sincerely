package com.github.tartaricacid.tlm_sincerely.client;

import com.github.tartaricacid.tlm_sincerely.client.gui.ConfigScreen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only hooks invoked from {@code SincerelyMod}'s constructor. This
 * class is only loaded when the dist check passed, so referencing client
 * classes here is safe on a dedicated server.
 */
public final class ClientSetupHooks {
    private ClientSetupHooks() {
    }

    /** Registers this addon's Cloth Config screen as a NeoForge extension point. */
    public static void registerConfigScreen(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (c, parent) -> ConfigScreen.create().setParentScreen(parent).build());
    }
}
