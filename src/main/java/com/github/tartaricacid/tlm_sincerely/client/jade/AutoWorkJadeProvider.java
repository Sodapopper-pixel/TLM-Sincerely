package com.github.tartaricacid.tlm_sincerely.client.jade;

import com.github.tartaricacid.tlm_sincerely.SincerelyExtension;
import com.github.tartaricacid.tlm_sincerely.client.network.ClientAutoWorkService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkState;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkTaskDataKeys;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade line shown only while this maid has auto work enabled and the global
 * automatic scheduling switch is on. The maid's TLM task data is synced to
 * the client, so no additional packet is needed. The global switch itself is
 * COMMON config which NeoForge does not sync; the server-authoritative value
 * travels in the auto work snapshot (local fallback until it arrives).
 */
public enum AutoWorkJadeProvider implements IEntityComponentProvider {
    INSTANCE;

    private static final ResourceLocation UID =
            new ResourceLocation(SincerelyExtension.MOD_ID, "auto_work");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
        if (!(accessor.getEntity() instanceof EntityMaid maid)) {
            return;
        }
        if (!ClientAutoWorkService.get().globalEnabledOrDefault()) {
            return;
        }
        AutoWorkState state = maid.getData(AutoWorkTaskDataKeys.STATE_KEY);
        if (state == null || !state.enabled()) {
            return;
        }
        tooltip.add(Component.translatable("jade.tlm_sincerely.auto_work.active"));
    }
}
