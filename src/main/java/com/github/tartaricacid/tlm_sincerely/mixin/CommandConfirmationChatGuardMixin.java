package com.github.tartaricacid.tlm_sincerely.mixin;

import com.github.tartaricacid.tlm_sincerely.command.CommandConfirmationService;
import com.github.tartaricacid.tlm_sincerely.memory.MemoryMaintenanceManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rejects new conversations while the maid has a pending command confirmation
 * (plan D8).
 *
 * <p>与 ChatMaintenanceGuardMixin 同时注入 chat() 的 HEAD。两个注入各自持有独立的
 * CallbackInfo，本 handler 无法感知对方的取消状态，因此不做 isCancelled 检查；
 * 重复发言由 Mixin 的取消语义天然避免——任一 guard 调用 cancel() 后目标方法立即
 * 返回，后应用的 guard handler 与方法体都不会再执行，两个提示永远不会同时发出。
 */
@Mixin(value = MaidAIChatManager.class, remap = false)
public abstract class CommandConfirmationChatGuardMixin {

    @Inject(method = "chat", at = @At("HEAD"), cancellable = true)
    private void onChat(String message, ChatClientInfo clientInfo, ServerPlayer player, CallbackInfo ci) {
        MaidAIChatManager self = (MaidAIChatManager) (Object) this;
        EntityMaid maid = self.getMaid();
        if (maid == null || !(maid.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (MemoryMaintenanceManager.isInternalMaintenanceCall()) {
            return;
        }
        // Let ChatMaintenanceGuardMixin report the maintenance busy state.
        if (MemoryMaintenanceManager.isMaintaining(maid.getUUID())) {
            return;
        }

        CommandConfirmationService service =
                CommandConfirmationService.getOrNull(serverLevel.getServer());
        if (service == null || !service.hasPendingForMaid(maid.getUUID())) {
            return;
        }

        player.sendSystemMessage(Component.translatable(
                "command.tlm_sincerely.run_command.confirm_busy",
                maid.getName().getString()
        ).withStyle(ChatFormatting.YELLOW));
        ci.cancel();
    }
}
