package com.github.tartaricacid.tlm_sincerely.config;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.ChatBarConfig;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.MaidCommandConfig;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.MemoryConfig;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
import net.minecraftforge.common.ForgeConfigSpec;

public final class GeneralConfig {
    public static ForgeConfigSpec CONFIG;

    public static ForgeConfigSpec init() {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        ChatBarConfig.init(builder);
        PriorityConfig.init(builder);
        MemoryConfig.init(builder);
        MaidCommandConfig.init(builder);
        CONFIG = builder.build();
        return CONFIG;
    }
}
