package com.github.tartaricacid.tlm_sincerely.client.gui;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.ChatBarConfig;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.MemoryConfig;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
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

        ConfigCategory multiTask = builder.getOrCreateCategory(
                Component.translatable("config.tlm_sincerely.multi_task"));

        multiTask.addEntry(entryBuilder.startBooleanToggle(
                        Component.translatable("config.tlm_sincerely.multi_task.enabled"),
                        PriorityConfig.ENABLED.get())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.tlm_sincerely.multi_task.enabled.tooltip"))
                .setSaveConsumer(PriorityConfig.ENABLED::set)
                .build());

        multiTask.addEntry(entryBuilder.startIntField(
                        Component.translatable("config.tlm_sincerely.multi_task.poll_interval"),
                        PriorityConfig.COOLDOWN.get())
                .setDefaultValue(100)
                .setMin(20)
                .setMax(6000)
                .setTooltip(Component.translatable("config.tlm_sincerely.multi_task.poll_interval.tooltip"))
                .setSaveConsumer(PriorityConfig.COOLDOWN::set)
                .build());

        ConfigCategory memory = builder.getOrCreateCategory(
                Component.translatable("config.tlm_sincerely.memory"));

        memory.addEntry(entryBuilder.startBooleanToggle(
                        Component.translatable("config.tlm_sincerely.memory.enabled"),
                        MemoryConfig.ENABLED.get())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.tlm_sincerely.memory.enabled.tooltip"))
                .setSaveConsumer(MemoryConfig.ENABLED::set)
                .build());

        memory.addEntry(entryBuilder.startIntField(
                        Component.translatable("config.tlm_sincerely.memory.max_memories"),
                        MemoryConfig.MAX_MEMORIES.get())
                .setDefaultValue(50)
                .setMin(1)
                .setMax(200)
                .setTooltip(Component.translatable("config.tlm_sincerely.memory.max_memories.tooltip"))
                .setSaveConsumer(MemoryConfig.MAX_MEMORIES::set)
                .build());

        memory.addEntry(entryBuilder.startIntField(
                        Component.translatable("config.tlm_sincerely.memory.core_limit"),
                        MemoryConfig.CORE_LIMIT.get())
                .setDefaultValue(10)
                .setMin(0)
                .setMax(50)
                .setTooltip(Component.translatable("config.tlm_sincerely.memory.core_limit.tooltip"))
                .setSaveConsumer(MemoryConfig.CORE_LIMIT::set)
                .build());

        memory.addEntry(entryBuilder.startIntField(
                        Component.translatable("config.tlm_sincerely.memory.preview_length"),
                        MemoryConfig.CONTEXT_PREVIEW_LENGTH.get())
                .setDefaultValue(30)
                .setMin(10)
                .setMax(200)
                .setTooltip(Component.translatable("config.tlm_sincerely.memory.preview_length.tooltip"))
                .setSaveConsumer(MemoryConfig.CONTEXT_PREVIEW_LENGTH::set)
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
