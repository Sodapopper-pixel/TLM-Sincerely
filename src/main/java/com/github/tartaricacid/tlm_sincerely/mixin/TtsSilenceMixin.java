package com.github.tartaricacid.tlm_sincerely.mixin;

import com.github.tartaricacid.tlm_sincerely.memory.MemoryMaintenanceManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MaidAIChatManager.class, remap = false)
public abstract class TtsSilenceMixin {

    @Inject(method = "tts", at = @At("HEAD"), cancellable = true)
    private void onTts(TTSSite site, String chatText, String ttsText, long waitingChatBubbleId, CallbackInfo ci) {
        MaidAIChatManager self = (MaidAIChatManager) (Object) this;
        if (MemoryMaintenanceManager.isMaintaining(self.getMaid().getUUID())) {
            ci.cancel();
        }
    }
}
