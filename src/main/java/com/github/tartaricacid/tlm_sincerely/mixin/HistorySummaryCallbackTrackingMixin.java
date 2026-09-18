package com.github.tartaricacid.tlm_sincerely.mixin;

import com.github.tartaricacid.tlm_sincerely.memory.MemoryChatTracker;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.summary.HistorySummaryCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.http.HttpRequest;

@Mixin(value = HistorySummaryCallback.class, remap = false)
public abstract class HistorySummaryCallbackTrackingMixin {
    @Inject(method = "onSuccess(Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/response/ResponseChat;)V",
            at = @At("RETURN"), remap = false)
    private void tlmSincerely$completeSummarySuccess(ResponseChat response, CallbackInfo ci) {
        LLMCallback callback = (LLMCallback) (Object) this;
        MemoryChatTracker.complete(callback, callback.getMaid());
    }

    @Inject(method = "onFailure(Ljava/net/http/HttpRequest;Ljava/lang/Throwable;I)V",
            at = @At("RETURN"), remap = false)
    private void tlmSincerely$completeSummaryFailure(HttpRequest request, Throwable throwable, int code, CallbackInfo ci) {
        LLMCallback callback = (LLMCallback) (Object) this;
        MemoryChatTracker.complete(callback, callback.getMaid());
    }
}
