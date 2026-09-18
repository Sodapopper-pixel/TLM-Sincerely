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
 * (plan D8). The message is only sent when the maintenance guard did not
 * already cancel the same call, so the two HEAD injections never speak twice.
 */
@Mixin(value = MaidAIChatManager.class, remap = false)
public abstract class CommandConfirmationChatGuardMixin {

    @Inject(method = "chat", at = @At("HEAD"), cancellable = true)
    private void onChat(String message, ChatClientInfo clientInfo, ServerPlayer player, CallbackInfo ci) {
        if (ci.isCancelled()) {
            return;
        }
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
