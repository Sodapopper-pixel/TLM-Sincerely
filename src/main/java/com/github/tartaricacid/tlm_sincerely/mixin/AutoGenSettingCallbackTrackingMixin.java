package com.github.tartaricacid.tlm_sincerely.mixin;

import com.github.tartaricacid.tlm_sincerely.memory.MemoryChatTracker;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.AutoGenSettingCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AutoGenSettingCallback.class, remap = false)
public abstract class AutoGenSettingCallbackTrackingMixin {
    @Inject(method = "onSuccess(Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/response/ResponseChat;)V",
            at = @At("RETURN"), remap = false)
    private void tlmSincerely$completeAutoGenSuccess(ResponseChat response, CallbackInfo ci) {
        LLMCallback callback = (LLMCallback) (Object) this;
        MemoryChatTracker.complete(callback, callback.getMaid());
    }
}
