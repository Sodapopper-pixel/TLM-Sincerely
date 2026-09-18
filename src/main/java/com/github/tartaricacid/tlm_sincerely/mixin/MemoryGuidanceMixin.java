package com.github.tartaricacid.tlm_sincerely.mixin;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.MemoryConfig;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.summary.HistorySummaryManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(value = MaidAIChatManager.class, remap = false)
public abstract class MemoryGuidanceMixin {

    private static final String GUIDANCE_TEXT = """
            ## Memory Guidelines
            You have persistent memory, listed under "Maid persistent memories" in context. Manage it with the tlm_memory tool.
            Call remember when: the player explicitly asks you to remember; the player states a lasting preference, personal fact, or preferred form of address; a significant event occurs (gift, promise, milestone).
            Do NOT remember: small talk, weather, transient game state, anything not said to you directly, anything already available via query_game_context.
            Key: short semantic English snake_case (e.g. player_hobby). Value: one concise sentence with context (who/what/when). Importance: core = explicitly requested or relationship-defining; archive = contextual details.
            Forget only when the player explicitly asks. Capacity is limited; when full, the oldest archive entry is evicted automatically.
            Use search to find old memories, recall to read full detail.""";

    private static final String ROUTING_TEMPLATE = """
            You are in the maid entity @%s, and tool-call directives will act on that entity. \
            Your self identity and persona come from the character setting, not from @%s.""";

    @Redirect(
            method = "buildMessage",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/summary/HistorySummaryManager;appendSummaryMessage(Ljava/util/List;)V"
            )
    )
    private void injectSincerelyGuidance(HistorySummaryManager manager, List<LLMMessage> list) {
        MaidAIChatManager self = (MaidAIChatManager) (Object) this;
        EntityMaid maid = self.getMaid();
        String routingName = maid.getName().getString();
        list.add(LLMMessage.systemChat(maid, ROUTING_TEMPLATE.formatted(routingName, routingName)));
        if (MemoryConfig.ENABLED.get() && MemoryConfig.MEMORY_GUIDANCE.get()) {
            list.add(LLMMessage.systemChat(maid, GUIDANCE_TEXT));
        }
        manager.appendSummaryMessage(list);
    }
}
