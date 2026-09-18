package com.github.tartaricacid.tlm_sincerely;

import com.github.tartaricacid.tlm_sincerely.command.AutoWorkCompatCommand;
import com.github.tartaricacid.tlm_sincerely.command.AutoWorkPresetCommand;
import com.github.tartaricacid.tlm_sincerely.command.ChatCommand;
import com.github.tartaricacid.tlm_sincerely.command.CommandConfirmationService;
import com.github.tartaricacid.tlm_sincerely.command.ConfirmCommand;
import com.github.tartaricacid.tlm_sincerely.command.MemoryCommand;
import com.github.tartaricacid.tlm_sincerely.command.UnicodeWordArgument;
import com.github.tartaricacid.tlm_sincerely.config.GeneralConfig;
import com.github.tartaricacid.tlm_sincerely.memory.MaidMemoryManager;
import com.github.tartaricacid.tlm_sincerely.memory.MemoryMaintenanceManager;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPresetService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkStateService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.compat.AutoWorkCompatService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.menu.AutoWorkMenus;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkNetworking;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkServerHandler;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.push.AutoWorkPushService;
import com.github.tartaricacid.tlm_sincerely.priority.detection.compat.CompatDetectorBootstrap;
import com.github.tartaricacid.tlm_sincerely.priority.TaskAutoSwitchHandler;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.common.Mod;

@Mod(SincerelyExtension.MOD_ID)
public class SincerelyMod {
    private static boolean configRegistered = false;
    private static boolean argumentTypesRegistered = false;

    public SincerelyMod() {
        registerArgumentTypes();
        MinecraftForge.EVENT_BUS.register(this);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
        AutoWorkMenus.register(FMLJavaModLoadingContext.get().getModEventBus());
        if (!configRegistered) {
            configRegistered = true;
            ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, GeneralConfig.init());
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.github.tartaricacid.tlm_sincerely.client.gui.ConfigScreen.register());
        }
    }

    @SubscribeEvent
    public void onCommonSetup(FMLCommonSetupEvent event) {
        AutoWorkNetworking.register();
        CompatDetectorBootstrap.register();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ChatCommand.register(event.getDispatcher());
        MemoryCommand.register(event.getDispatcher());
        AutoWorkCompatCommand.register(event.getDispatcher());
        AutoWorkPresetCommand.register(event.getDispatcher());
        ConfirmCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            MemoryMaintenanceManager.onServerTick(event.getServer());
            AutoWorkCompatService compatService = AutoWorkCompatService.getOrNull(event.getServer());
            if (compatService != null) {
                compatService.onServerTick();
            }
            CommandConfirmationService confirmationService =
                    CommandConfirmationService.getOrNull(event.getServer());
            if (confirmationService != null) {
                confirmationService.onServerTick();
            }
            AutoWorkPushService pushService = AutoWorkPushService.getOrNull(event.getServer());
            if (pushService != null) {
                pushService.onServerTick();
            }
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        AutoWorkPresetService.bind(event.getServer());
        AutoWorkStateService.bind(event.getServer());
        AutoWorkCompatService.bind(event.getServer());
        CommandConfirmationService.bind(event.getServer());
        AutoWorkPushService.bind(event.getServer());
        TaskAutoSwitchHandler.requestServerRebindRescans(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        AutoWorkPushService.unbind(event.getServer());
        CommandConfirmationService.unbind(event.getServer());
        AutoWorkCompatService.unbind(event.getServer());
        AutoWorkStateService.unbind(event.getServer());
        AutoWorkPresetService.unbind(event.getServer());
        MemoryMaintenanceManager.clearRuntimeState();
        MaidMemoryManager.clearCache();
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            CommandConfirmationService service = CommandConfirmationService.getOrNull(player.server);
            if (service != null) {
                service.onOwnerLoggedOut(player);
            }
            AutoWorkPushService pushService = AutoWorkPushService.getOrNull(player.server);
            if (pushService != null) {
                pushService.onPlayerLoggedOut(player);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            AutoWorkCompatService compatService = AutoWorkCompatService.getOrNull(player.server);
            if (compatService != null) {
                compatService.queueLoginReminder(player);
            }
            AutoWorkServerHandler.sendSeed(player);
            AutoWorkServerHandler.sendSnapshot(player);
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
}
