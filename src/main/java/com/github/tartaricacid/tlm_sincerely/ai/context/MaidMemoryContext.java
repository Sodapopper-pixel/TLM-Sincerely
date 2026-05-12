package com.github.tartaricacid.tlm_sincerely.ai.context;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.MemoryConfig;
import com.github.tartaricacid.tlm_sincerely.memory.MaidMemory;
import com.github.tartaricacid.tlm_sincerely.memory.MaidMemoryManager;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.AbstractMaidContext;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

public class MaidMemoryContext extends AbstractMaidContext {
    public MaidMemoryContext() {
        super("tlm_sincerely_memory_list", "Maid persistent memories");
    }

    @Override
    public String getValue(EntityMaid maid) {
        if (!MemoryConfig.ENABLED.get()) {
            return "Memory system disabled";
        }

        MaidMemory memory = MaidMemoryManager.load(maid.getUUID());
        if (memory.isEmpty()) {
            return "None";
        }

        return memory.generateContextPreview(
                MemoryConfig.CORE_LIMIT.get(),
                MemoryConfig.CONTEXT_PREVIEW_LENGTH.get()
        );
    }
}
