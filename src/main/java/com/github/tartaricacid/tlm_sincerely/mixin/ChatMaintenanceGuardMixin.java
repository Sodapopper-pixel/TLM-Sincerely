package com.github.tartaricacid.tlm_sincerely.mixin;

import com.github.tartaricacid.tlm_sincerely.memory.MemoryMaintenanceManager;
import com.github.tartaricacid.tlm_sincerely.memory.MemoryChatTracker;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(value = MaidAIChatManager.class, remap = false)
public abstract class ChatMaintenanceGuardMixin {

    @Inject(method = "chat", at = @At("HEAD"), cancellable = true)
    private void onChat(String message, ChatClientInfo clientInfo, ServerPlayer player, CallbackInfo ci) {
        MaidAIChatManager self = (MaidAIChatManager) (Object) this;
        UUID maidUuid = self.getMaid().getUUID();

        if (MemoryMaintenanceManager.isMaintaining(maidUuid)
                && !MemoryMaintenanceManager.isInternalMaintenanceCall()) {
            player.sendSystemMessage(Component.translatable(
                    "command.tlm_sincerely.memory.maintenance_busy",
                    self.getMaid().getName().getString()
            ).withStyle(net.minecraft.ChatFormatting.YELLOW));
            ci.cancel();
            return;
        }
        if (!MemoryMaintenanceManager.isInternalMaintenanceCall()) {
            MemoryChatTracker.beginOrdinaryChat(maidUuid);
        }
    }

    @Inject(method = "chat", at = @At("RETURN"))
    private void onChatReturn(String message, ChatClientInfo clientInfo, ServerPlayer player, CallbackInfo ci) {
        if (MemoryMaintenanceManager.isInternalMaintenanceCall()) {
            return;
        }
        MaidAIChatManager self = (MaidAIChatManager) (Object) this;
        MemoryChatTracker.finishSynchronousSubmission(self.getMaid().getUUID(), self.historySummaryRunning);
    }
}
