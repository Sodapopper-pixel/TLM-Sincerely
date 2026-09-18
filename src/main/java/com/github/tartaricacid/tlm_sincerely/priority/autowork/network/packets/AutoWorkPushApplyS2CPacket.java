package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.client.autowork.AutoWorkClientLibrary;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPreset;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkPresetData;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S2C: the payload of an accepted preset push. The client writes it into its
 * private library (UUID-preserving) and reports a local summary.
 */
public final class AutoWorkPushApplyS2CPacket {
    public static final int INDEX = 12;

    private final List<AutoWorkPresetData> presets;

    public AutoWorkPushApplyS2CPacket(List<AutoWorkPresetData> presets) {
        this.presets = presets;
    }

    public static void encode(AutoWorkPushApplyS2CPacket msg, FriendlyByteBuf buf) {
        AutoWorkPresetData.encodeList(buf, msg.presets);
    }

    public static AutoWorkPushApplyS2CPacket decode(FriendlyByteBuf buf) {
        return new AutoWorkPushApplyS2CPacket(AutoWorkPresetData.decodeList(buf));
    }

    public static void handle(AutoWorkPushApplyS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            List<AutoWorkPreset> presets = new ArrayList<>(msg.presets.size());
            for (AutoWorkPresetData data : msg.presets) {
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
        }));
        ctx.get().setPacketHandled(true);
    }

    public List<AutoWorkPresetData> presets() {
        return presets;
    }
}
