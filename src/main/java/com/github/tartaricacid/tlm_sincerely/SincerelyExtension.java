package com.github.tartaricacid.tlm_sincerely;

import com.github.tartaricacid.tlm_sincerely.ai.tool.TaskPriorityTool;
import com.github.tartaricacid.tlm_sincerely.client.gui.ConfigScreen;
import com.github.tartaricacid.tlm_sincerely.client.gui.priority.PriorityContainerGui;
import com.github.tartaricacid.tlm_sincerely.client.gui.priority.PriorityRegistry;
import com.github.tartaricacid.tlm_sincerely.command.ChatCommand;
import com.github.tartaricacid.tlm_sincerely.config.GeneralConfig;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister;
import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(SincerelyExtension.MOD_ID)
@LittleMaidExtension
public class SincerelyExtension implements ILittleMaid {
    public static final String MOD_ID = "tlm_sincerely";
    private static boolean configRegistered = false;

    public SincerelyExtension() {
        MinecraftForge.EVENT_BUS.register(this);
        PriorityRegistry.MENU_TYPES.register(FMLJavaModLoadingContext.get().getModEventBus());
        if (!configRegistered) {
            configRegistered = true;
            ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, GeneralConfig.init());
            ConfigScreen.register();
        }
    }

    @Override
    public void registerAITool(ToolRegister register) {
        register.register(new TaskPriorityTool());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ChatCommand.register(event.getDispatcher());
    }

    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> MenuScreens.register(
                    PriorityRegistry.PRIORITY_CONTAINER.get(), PriorityContainerGui::new));
        }
    }
}