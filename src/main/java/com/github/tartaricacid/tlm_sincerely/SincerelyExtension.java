package com.github.tartaricacid.tlm_sincerely;

import com.github.tartaricacid.tlm_sincerely.ai.context.MaidMemoryContext;
import com.github.tartaricacid.tlm_sincerely.ai.tool.AutoWorkTool;
import com.github.tartaricacid.tlm_sincerely.ai.tool.MaidMemoryTool;
import com.github.tartaricacid.tlm_sincerely.client.gui.ConfigScreen;
import com.github.tartaricacid.tlm_sincerely.command.ChatCommand;
import com.github.tartaricacid.tlm_sincerely.command.MemoryCommand;
import com.github.tartaricacid.tlm_sincerely.command.UnicodeWordArgument;
import com.github.tartaricacid.tlm_sincerely.config.GeneralConfig;
import com.github.tartaricacid.tlm_sincerely.memory.MemoryMaintenanceManager;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPresetService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkStateService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkTaskDataKeys;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.menu.AutoWorkMenus;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkNetworking;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.GameContextRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister;
import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.data.TaskDataRegister;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod(SincerelyExtension.MOD_ID)
@LittleMaidExtension
public class SincerelyExtension implements ILittleMaid {
    public static final String MOD_ID = "tlm_sincerely";
    private static boolean configRegistered = false;
    private static boolean argumentTypesRegistered = false;

    public SincerelyExtension() {
        registerArgumentTypes();
        MinecraftForge.EVENT_BUS.register(this);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
        // Register the auto work MenuType deferred register on the mod
        // bus so the auto_work_config entry is created before the
        // registry event freezes the registry.
        AutoWorkMenus.register(FMLJavaModLoadingContext.get().getModEventBus());
        if (!configRegistered) {
            configRegistered = true;
            ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, GeneralConfig.init());
            ConfigScreen.register();
        }
    }

    @SubscribeEvent
    public void onCommonSetup(FMLCommonSetupEvent event) {
        // Register the auto work network channel during common setup so
        // both physical sides see the same packet indices. The
        // mod constructor is automatically subscribed to the mod bus
        // via the @Mod annotation, so no manual listener registration
        // is required.
        AutoWorkNetworking.register();
    }

    private static void registerArgumentTypes() {
        if (argumentTypesRegistered) {
            return;
        }
        argumentTypesRegistered = true;
        ArgumentTypeInfos.registerByClass(
                UnicodeWordArgument.class,
                SingletonArgumentInfo.contextFree(() -> UnicodeWordArgument.word(""))
        );
    }

    @Override
    public void registerAITool(ToolRegister register) {
        register.register(new AutoWorkTool());
        register.register(new MaidMemoryTool());
    }

    @Override
    public void registerTaskData(TaskDataRegister register) {
        register.register(AutoWorkTaskDataKeys.STATE_KEY);
    }

    @Override
    public void registerAIMaidContext(GameContextRegister register) {
        register.registerCategory("tlm_sincerely_memory",
                "Maid persistent memories: key-value facts about the player, past events, preferences",
                true);
        register.registerContext("tlm_sincerely_memory", new MaidMemoryContext());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ChatCommand.register(event.getDispatcher());
        MemoryCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            MemoryMaintenanceManager.onServerTick(event.getServer());
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // Order matters: presets first (they own the default id), then
        // state (which can fall back to the default id on cold reads).
        AutoWorkPresetService.bind(event.getServer());
        AutoWorkStateService.bind(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        AutoWorkStateService.unbind(event.getServer());
        AutoWorkPresetService.unbind(event.getServer());
    }
}
