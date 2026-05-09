package com.github.tartaricacid.tlm_sincerely.config;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.ChatBarConfig;
import net.minecraftforge.common.ForgeConfigSpec;

public final class GeneralConfig {
    public static ForgeConfigSpec CONFIG;

    public static ForgeConfigSpec init() {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        ChatBarConfig.init(builder);
        CONFIG = builder.build();
        return CONFIG;
    }
}
