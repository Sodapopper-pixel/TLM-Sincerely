package com.github.tartaricacid.tlm_sincerely.config;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.ChatBarConfig;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.MaidCommandConfig;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.MemoryConfig;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class GeneralConfig {
    public static ModConfigSpec CONFIG;

    public static ModConfigSpec init() {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        ChatBarConfig.init(builder);
        PriorityConfig.init(builder);
        MemoryConfig.init(builder);
        MaidCommandConfig.init(builder);
        CONFIG = builder.build();
        return CONFIG;
    }
}
