package com.github.tartaricacid.tlm_sincerely.client.gui;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.ChatBarConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;

public final class ConfigScreen {
    public static ConfigBuilder create() {
        ConfigBuilder builder = ConfigBuilder.create()
                .setTitle(Component.literal("TLM Sincerely"))
                .setParentScreen(null);
        builder.setGlobalized(true);
        builder.setGlobalizedExpanded(false);

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();
        ConfigCategory chatbar = builder.getOrCreateCategory(
                Component.translatable("config.tlm_sincerely.chatbar"));

        chatbar.addEntry(entryBuilder.startBooleanToggle(
                        Component.translatable("config.tlm_sincerely.chatbar.chat_mode"),
                        ChatBarConfig.CHAT_MODE.get())
                .setDefaultValue(false)
                .setTooltip(Component.translatable("config.tlm_sincerely.chatbar.chat_mode.tooltip"))
                .setSaveConsumer(ChatBarConfig.CHAT_MODE::set)
                .build());

        chatbar.addEntry(entryBuilder.startBooleanToggle(
                        Component.translatable("config.tlm_sincerely.chatbar.global_visible"),
                        ChatBarConfig.GLOBAL_VISIBLE.get())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.tlm_sincerely.chatbar.global_visible.tooltip"))
                .setSaveConsumer(ChatBarConfig.GLOBAL_VISIBLE::set)
                .build());

        chatbar.addEntry(entryBuilder.startBooleanToggle(
                        Component.translatable("config.tlm_sincerely.chatbar.require_prefix"),
                        ChatBarConfig.REQUIRE_PREFIX.get())
                .setDefaultValue(false)
                .setTooltip(Component.translatable("config.tlm_sincerely.chatbar.require_prefix.tooltip"))
                .setSaveConsumer(ChatBarConfig.REQUIRE_PREFIX::set)
                .build());

        chatbar.addEntry(entryBuilder.startDoubleField(
                        Component.translatable("config.tlm_sincerely.chatbar.auto_chat_range"),
                        ChatBarConfig.AUTO_CHAT_RANGE.get())
                .setDefaultValue(5.0)
                .setMin(0.0)
                .setMax(64.0)
                .setTooltip(Component.translatable("config.tlm_sincerely.chatbar.auto_chat_range.tooltip"))
                .setSaveConsumer(ChatBarConfig.AUTO_CHAT_RANGE::set)
                .build());

        chatbar.addEntry(entryBuilder.startTextField(
                        Component.translatable("config.tlm_sincerely.chatbar.prefix_pattern"),
                        ChatBarConfig.PREFIX_PATTERN.get())
                .setDefaultValue("@")
                .setTooltip(Component.translatable("config.tlm_sincerely.chatbar.prefix_pattern.tooltip"))
                .setSaveConsumer(ChatBarConfig.PREFIX_PATTERN::set)
                .build());

        return builder;
    }

    public static void register() {
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (client, parent) -> create().setParentScreen(parent).build()));
    }
}
