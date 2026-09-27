package com.github.tartaricacid.tlm_sincerely.client.network;

import com.github.tartaricacid.tlm_sincerely.client.autowork.AutoWorkClientLibrary;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPreset;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkPresetData;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AutoWorkPushApplyS2CPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AutoWorkSeedS2CPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AutoWorkSnapshotS2CPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side handlers for the auto work S2C payloads.
 *
 * <p>This class touches client-only classes ({@link Minecraft} etc.), so it
 * must never be loaded on a dedicated server. NeoForge 1.21 only offers the
 * three-arg {@code playToClient} overload (there is no client-side payload
 * registration event on this version), therefore the payload classes resolve
 * these handlers lazily from inside their {@code handle} methods; those are
 * only ever invoked for clientbound traffic, and the flow guard in each
 * payload keeps the reference from resolving elsewhere.
 */
public final class AutoWorkClientPayloadHandlers {
    private AutoWorkClientPayloadHandlers() {
    }

    /** Stores the received snapshot into the client cache. */
    public static void handleSnapshot(AutoWorkSnapshotS2CPacket msg) {
        ClientAutoWorkService.get().accept(msg.snapshot());
    }

    /** Imports the server's frozen seed library (first-join copy only). */
    public static void handleSeed(AutoWorkSeedS2CPacket msg) {
        List<AutoWorkPreset> presets = new ArrayList<>(msg.presets().size());
        for (AutoWorkPresetData data : msg.presets()) {
            presets.add(data.toPreset());
        }
        AutoWorkClientLibrary.get().acceptSeed(msg.defaultPresetId(), presets);
    }

    /** Writes pushed presets into the library and reports a local summary. */
    public static void handlePushApply(AutoWorkPushApplyS2CPacket msg) {
        List<AutoWorkPreset> presets = new ArrayList<>(msg.presets().size());
        for (AutoWorkPresetData data : msg.presets()) {
            presets.add(data.toPreset());
        }
        AutoWorkClientLibrary.PushResult result = AutoWorkClientLibrary.get().applyPush(presets);
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendSystemMessage(Component.translatable(
                    "command.tlm_sincerely.autowork.push.applied",
                    Component.translatable("command.tlm_sincerely.autowork.push.new",
                            String.valueOf(result.created())),
                    Component.translatable("command.tlm_sincerely.autowork.push.update",
                            String.valueOf(result.updated())),
                    Component.translatable("command.tlm_sincerely.autowork.push.rename",
                            String.valueOf(result.renamed()))));
        }
    }
}
