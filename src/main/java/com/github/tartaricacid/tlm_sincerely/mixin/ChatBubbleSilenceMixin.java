package com.github.tartaricacid.tlm_sincerely.mixin;

import com.github.tartaricacid.tlm_sincerely.memory.MemoryMaintenanceManager;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChatBubbleManager.class, remap = false)
public class ChatBubbleSilenceMixin {

    @Shadow
    @Final
    private EntityMaid maid;

    @Inject(method = "addLLMChatText", at = @At("HEAD"), cancellable = true)
    private void onAddLLMChatText(String text, long gameTime, CallbackInfo ci) {
        if (MemoryMaintenanceManager.isMaintaining(maid.getUUID())) {
            ci.cancel();
        }
    }

    @Inject(method = "addThinkingText(Ljava/lang/String;)J", at = @At("HEAD"), cancellable = true)
    private void onAddThinkingText(String text, CallbackInfoReturnable<Long> cir) {
        if (MemoryMaintenanceManager.isMaintaining(maid.getUUID())) {
            cir.setReturnValue(-1L);
        }
    }
}
