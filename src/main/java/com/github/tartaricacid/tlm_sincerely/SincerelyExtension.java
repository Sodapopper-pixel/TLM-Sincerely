package com.github.tartaricacid.tlm_sincerely;

import com.github.tartaricacid.tlm_sincerely.ai.context.MaidMemoryContext;
import com.github.tartaricacid.tlm_sincerely.ai.tool.MaidMemoryTool;
import com.github.tartaricacid.tlm_sincerely.ai.tool.TaskPriorityTool;
import com.github.tartaricacid.tlm_sincerely.client.gui.ConfigScreen;
import com.github.tartaricacid.tlm_sincerely.command.ChatCommand;
import com.github.tartaricacid.tlm_sincerely.command.MemoryCommand;
import com.github.tartaricacid.tlm_sincerely.command.UnicodeWordArgument;
import com.github.tartaricacid.tlm_sincerely.config.GeneralConfig;
import com.github.tartaricacid.tlm_sincerely.memory.MemoryMaintenanceManager;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.GameContextRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister;
import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod(SincerelyExtension.MOD_ID)
@LittleMaidExtension
public class SincerelyExtension implements ILittleMaid {
    public static final String MOD_ID = "tlm_sincerely";
    private static boolean configRegistered = false;
    private static boolean argumentTypesRegistered = false;

    public SincerelyExtension() {
        registerArgumentTypes();
        MinecraftForge.EVENT_BUS.register(this);
        if (!configRegistered) {
            configRegistered = true;
            ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, GeneralConfig.init());
            ConfigScreen.register();
        }
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
        register.register(new TaskPriorityTool());
        register.register(new MaidMemoryTool());
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
}
