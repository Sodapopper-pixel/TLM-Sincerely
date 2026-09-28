package com.github.tartaricacid.tlm_sincerely;

import com.github.tartaricacid.tlm_sincerely.client.ClientSetupHooks;
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
import com.github.tartaricacid.tlm_sincerely.memory.compat.MaidFileManagerBridge;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPresetService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkStateService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.compat.AutoWorkCompatService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.menu.AutoWorkMenus;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkNetworking;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkServerHandler;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.push.AutoWorkPushService;
import com.github.tartaricacid.tlm_sincerely.priority.detection.compat.CompatDetectorBootstrap;
import com.github.tartaricacid.tlm_sincerely.priority.TaskAutoSwitchHandler;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

@Mod(SincerelyExtension.MOD_ID)
public class SincerelyMod {
    private static boolean configRegistered = false;

    public SincerelyMod(IEventBus modEventBus, ModContainer container) {
        NeoForge.EVENT_BUS.register(this);
        modEventBus.addListener(this::onCommonSetup);
        AutoWorkMenus.register(modEventBus);
        AutoWorkNetworking.register(modEventBus);
        COMMAND_ARGUMENT_TYPES.register(modEventBus);
        if (!configRegistered) {
            configRegistered = true;
            container.registerConfig(ModConfig.Type.COMMON, GeneralConfig.init());
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientSetupHooks.registerConfigScreen(container);
            }
        }
    }

    // FMLCommonSetupEvent 是 mod 总线事件，经构造器里的 modEventBus.addListener 注册；
    // 不能加 @SubscribeEvent（本类被 NeoForge.EVENT_BUS.register(this) 整体注册到 GAME 总线，
    // NeoForge 21.1 对错总线的监听器直接抛 IllegalArgumentException）。
    public void onCommonSetup(FMLCommonSetupEvent event) {
        CompatDetectorBootstrap.register();
        MaidFileManagerBridge.register();
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
    public void onServerTick(ServerTickEvent.Post event) {
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

    /**
     * 1.21.1 命令树包按 {@code COMMAND_ARGUMENT_TYPE} registry id 编解码：
     * 只调 {@link ArgumentTypeInfos#registerByClass}（仅填 BY_CLASS map）会导致序列化写出
     * id=-1、客户端按 id 反查得到 null 节点、字节流错位（进存档时
     * {@code Failed to decode packet 'clientbound/minecraft:commands'}）。
     * 必须经 DeferredRegister 注册进 registry，并在 supplier 里同时回填 BY_CLASS。
     */
    public static final DeferredRegister<ArgumentTypeInfo<?, ?>> COMMAND_ARGUMENT_TYPES =
            DeferredRegister.create(BuiltInRegistries.COMMAND_ARGUMENT_TYPE, SincerelyExtension.MOD_ID);

    public static final Supplier<SingletonArgumentInfo<UnicodeWordArgument>> UNICODE_WORD_INFO =
            COMMAND_ARGUMENT_TYPES.register("unicode_word", () ->
                    ArgumentTypeInfos.registerByClass(
                            UnicodeWordArgument.class,
                            SingletonArgumentInfo.contextFree(() -> UnicodeWordArgument.word(""))
                    ));
}
