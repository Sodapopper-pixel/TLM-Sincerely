package com.github.tartaricacid.tlm_sincerely.mixin;

import com.github.tartaricacid.tlm_sincerely.memory.MemoryChatTracker;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.http.HttpRequest;
import java.util.List;

@Mixin(value = LLMCallback.class, remap = false)
public abstract class LLMCallbackTrackingMixin {
    @Shadow
    @Final
    protected EntityMaid maid;

    @Inject(method = "<init>(Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/MaidAIChatManager;Ljava/util/List;Z)V",
            at = @At("RETURN"), remap = false)
    private void tlmSincerely$trackRequest(MaidAIChatManager manager, List<LLMMessage> messages,
                                            boolean silent, CallbackInfo ci) {
        MemoryChatTracker.register((LLMCallback) (Object) this, maid, messages);
    }

    @Inject(method = "onSuccess(Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/response/ResponseChat;)V",
            at = @At("RETURN"), remap = false)
    private void tlmSincerely$completeSuccess(ResponseChat response, CallbackInfo ci) {
        MemoryChatTracker.complete((LLMCallback) (Object) this, maid);
    }

    @Inject(method = "onFailure(Ljava/net/http/HttpRequest;Ljava/lang/Throwable;I)V",
            at = @At("RETURN"), remap = false)
    private void tlmSincerely$completeFailure(HttpRequest request, Throwable throwable, int code, CallbackInfo ci) {
        MemoryChatTracker.complete((LLMCallback) (Object) this, maid);
    }
}
