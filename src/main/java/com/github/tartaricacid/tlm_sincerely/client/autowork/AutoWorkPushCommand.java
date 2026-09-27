package com.github.tartaricacid.tlm_sincerely.client.autowork;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPreset;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkPresetData;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AutoWorkPushOfferC2SPacket;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Client-side {@code /tlmautowork preset send} command.
 *
 * <p>Preset data lives on the sending client, so the command resolves the
 * selection against the private library and hands it to the server via
 * {@link AutoWorkPushOfferC2SPacket}; the server only relays it after the
 * receiver confirms. Unknown client command paths fall through to the server
 * dispatcher, so {@code /tlmautowork compat ...} and the accept/reject
 * buttons keep working.
 */
@EventBusSubscriber(modid = SincerelyExtension.MOD_ID, value = Dist.CLIENT)
public final class AutoWorkPushCommand {
    private static final String BROADCAST_TARGET = "all";

    private AutoWorkPushCommand() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("tlmautowork")
                .then(Commands.literal("preset")
                        .then(Commands.literal("send")
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .executes(context -> send(context, false, ""))
                                        .then(Commands.argument("preset", StringArgumentType.string())
                                                .executes(context -> send(context, false,
                                                        StringArgumentType.getString(context, "preset"))))))
                        .then(Commands.literal("sendlibrary")
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .executes(context -> send(context, true, ""))))));
    }

    private static int send(CommandContext<CommandSourceStack> context, boolean wholeLibrary,
                            String rawPreset) {
        String target = StringArgumentType.getString(context, "target").trim();
        if (target.isEmpty()) {
            return 0;
        }
        boolean broadcast = BROADCAST_TARGET.equalsIgnoreCase(target);
        AutoWorkClientLibrary library = AutoWorkClientLibrary.get();

        List<AutoWorkPresetData> payload = new ArrayList<>();
        if (wholeLibrary) {
            for (AutoWorkPreset preset : library.presetsInOrder()) {
                payload.add(AutoWorkPresetData.from(preset));
            }
        } else {
            AutoWorkPreset preset = resolvePreset(library, rawPreset);
            if (preset == null) {
                error(Component.translatable("command.tlm_sincerely.autowork.push.preset_not_found", rawPreset));
                return 0;
            }
            payload.add(AutoWorkPresetData.from(preset));
        }
        if (payload.isEmpty()) {
            error(Component.translatable("command.tlm_sincerely.autowork.push.empty"));
            return 0;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new AutoWorkPushOfferC2SPacket(broadcast, broadcast ? "" : target, payload));
        return 1;
    }

    private static AutoWorkPreset resolvePreset(AutoWorkClientLibrary library, String raw) {
        if (raw == null || raw.isEmpty()) {
            return library.defaultPreset();
        }
        String trimmed = raw.trim();
        AutoWorkPreset byId = null;
        try {
            byId = library.getPreset(UUID.fromString(trimmed));
        } catch (IllegalArgumentException ignored) {
        }
        if (byId != null) {
            return byId;
        }
        for (AutoWorkPreset preset : library.presetsInOrder()) {
            if (preset.getName().equals(trimmed)) {
                return preset;
            }
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (AutoWorkPreset preset : library.presetsInOrder()) {
            if (preset.getName().toLowerCase(Locale.ROOT).equals(lower)) {
                return preset;
            }
        }
        return null;
    }

    private static void error(Component message) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendSystemMessage(message.copy().withStyle(ChatFormatting.RED));
        }
    }
}
