package com.github.tartaricacid.tlm_sincerely.client.gui;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.ChatBarConfig;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.MemoryConfig;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;

public final class ConfigScreen {
    public static ConfigBuilder create() {
        ConfigBuilder builder = ConfigBuilder.create()
                .setTitle(Component.literal("TLM-Sincerely"))
                .setParentScreen(null);
        builder.setGlobalized(true);
        builder.setGlobalizedExpanded(false);
        addEntries(builder, builder.entryBuilder());
        return builder;
    }

    /**
     * Adds this addon's categories to any compatible Cloth Config root. This
     * is shared by our Mod List config button and TLM's extension event.
     */
    public static void addEntries(ConfigBuilder builder, ConfigEntryBuilder entryBuilder) {
        ConfigCategory addon = builder.getOrCreateCategory(
                Component.translatable("config.tlm_sincerely"));

        SubCategoryBuilder chatbar = entryBuilder.startSubCategory(
                Component.translatable("config.tlm_sincerely.chatbar"));
        chatbar.setExpanded(true);

        chatbar.add(entryBuilder.startBooleanToggle(
                        Component.translatable("config.tlm_sincerely.chatbar.chat_mode"),
                        ChatBarConfig.CHAT_MODE.get())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.tlm_sincerely.chatbar.chat_mode.tooltip"))
                .setSaveConsumer(ChatBarConfig.CHAT_MODE::set)
                .build());

        chatbar.add(entryBuilder.startBooleanToggle(
                        Component.translatable("config.tlm_sincerely.chatbar.global_visible"),
                        ChatBarConfig.GLOBAL_VISIBLE.get())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.tlm_sincerely.chatbar.global_visible.tooltip"))
                .setSaveConsumer(ChatBarConfig.GLOBAL_VISIBLE::set)
                .build());

        chatbar.add(entryBuilder.startBooleanToggle(
                        Component.translatable("config.tlm_sincerely.chatbar.require_prefix"),
                        ChatBarConfig.REQUIRE_PREFIX.get())
                .setDefaultValue(false)
                .setTooltip(Component.translatable("config.tlm_sincerely.chatbar.require_prefix.tooltip"))
                .setSaveConsumer(ChatBarConfig.REQUIRE_PREFIX::set)
                .build());

        chatbar.add(entryBuilder.startDoubleField(
                        Component.translatable("config.tlm_sincerely.chatbar.auto_chat_range"),
                        ChatBarConfig.AUTO_CHAT_RANGE.get())
                .setDefaultValue(5.0)
                .setMin(0.0)
                .setMax(64.0)
                .setTooltip(Component.translatable("config.tlm_sincerely.chatbar.auto_chat_range.tooltip"))
                .setSaveConsumer(ChatBarConfig.AUTO_CHAT_RANGE::set)
                .build());

        chatbar.add(entryBuilder.startTextField(
                        Component.translatable("config.tlm_sincerely.chatbar.prefix_pattern"),
                        ChatBarConfig.PREFIX_PATTERN.get())
                .setDefaultValue("@")
                .setTooltip(Component.translatable("config.tlm_sincerely.chatbar.prefix_pattern.tooltip"))
                .setSaveConsumer(ChatBarConfig.PREFIX_PATTERN::set)
                .build());

        addon.addEntry(chatbar.build());

        SubCategoryBuilder multiTask = entryBuilder.startSubCategory(
                Component.translatable("config.tlm_sincerely.multi_task"));
        multiTask.setExpanded(true);

        multiTask.add(entryBuilder.startBooleanToggle(
                        Component.translatable("config.tlm_sincerely.multi_task.enabled"),
                        PriorityConfig.ENABLED.get())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.tlm_sincerely.multi_task.enabled.tooltip"))
                .setSaveConsumer(PriorityConfig.ENABLED::set)
                .build());

        multiTask.add(entryBuilder.startIntField(
                        Component.translatable("config.tlm_sincerely.multi_task.poll_interval"),
                        PriorityConfig.COOLDOWN.get())
                .setDefaultValue(100)
                .setMin(20)
                .setMax(6000)
                .setTooltip(Component.translatable("config.tlm_sincerely.multi_task.poll_interval.tooltip"))
                .setSaveConsumer(PriorityConfig.COOLDOWN::set)
                .build());

        multiTask.add(entryBuilder.startBooleanToggle(
                        Component.translatable("config.tlm_sincerely.multi_task.experimental_attack_preempt"),
                        PriorityConfig.EXPERIMENTAL_ATTACK_PREEMPT.get())
                .setDefaultValue(false)
                .setTooltip(Component.translatable("config.tlm_sincerely.multi_task.experimental_attack_preempt.tooltip"))
                .setSaveConsumer(PriorityConfig.EXPERIMENTAL_ATTACK_PREEMPT::set)
                .build());

        multiTask.add(entryBuilder.startBooleanToggle(
                        Component.translatable("config.tlm_sincerely.multi_task.force_brain_refresh_on_stuck"),
                        PriorityConfig.FORCE_BRAIN_REFRESH_ON_STUCK.get())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.tlm_sincerely.multi_task.force_brain_refresh_on_stuck.tooltip"))
                .setSaveConsumer(PriorityConfig.FORCE_BRAIN_REFRESH_ON_STUCK::set)
                .build());

        multiTask.add(entryBuilder.startIntField(
                        Component.translatable("config.tlm_sincerely.multi_task.available_confirmations"),
                        PriorityConfig.AVAILABLE_CONFIRMATIONS.get())
                .setDefaultValue(1)
                .setMin(1)
                .setMax(20)
                .setTooltip(Component.translatable("config.tlm_sincerely.multi_task.available_confirmations.tooltip"))
                .setSaveConsumer(PriorityConfig.AVAILABLE_CONFIRMATIONS::set)
                .build());

        multiTask.add(entryBuilder.startIntField(
                        Component.translatable("config.tlm_sincerely.multi_task.unavailable_confirmations"),
                        PriorityConfig.UNAVAILABLE_CONFIRMATIONS.get())
                .setDefaultValue(2)
                .setMin(1)
                .setMax(20)
                .setTooltip(Component.translatable("config.tlm_sincerely.multi_task.unavailable_confirmations.tooltip"))
                .setSaveConsumer(PriorityConfig.UNAVAILABLE_CONFIRMATIONS::set)
                .build());

        multiTask.add(entryBuilder.startIntField(
                        Component.translatable("config.tlm_sincerely.multi_task.minimum_task_hold_ticks"),
                        PriorityConfig.MINIMUM_TASK_HOLD_TICKS.get())
                .setDefaultValue(60)
                .setMin(0)
                .setMax(12000)
                .setTooltip(Component.translatable("config.tlm_sincerely.multi_task.minimum_task_hold_ticks.tooltip"))
                .setSaveConsumer(PriorityConfig.MINIMUM_TASK_HOLD_TICKS::set)
                .build());

        multiTask.add(entryBuilder.startIntField(
                        Component.translatable("config.tlm_sincerely.multi_task.detection_block_budget"),
                        PriorityConfig.DETECTION_BLOCK_BUDGET_PER_TICK.get())
                .setDefaultValue(256)
                .setMin(16)
                .setMax(4096)
                .setTooltip(Component.translatable("config.tlm_sincerely.multi_task.detection_block_budget.tooltip"))
                .setSaveConsumer(PriorityConfig.DETECTION_BLOCK_BUDGET_PER_TICK::set)
                .build());

        multiTask.add(entryBuilder.startIntField(
                        Component.translatable("config.tlm_sincerely.multi_task.path_check_budget"),
                        PriorityConfig.PATH_CHECK_BUDGET_PER_TICK.get())
                .setDefaultValue(4)
                .setMin(1)
                .setMax(128)
                .setTooltip(Component.translatable("config.tlm_sincerely.multi_task.path_check_budget.tooltip"))
                .setSaveConsumer(PriorityConfig.PATH_CHECK_BUDGET_PER_TICK::set)
                .build());

        addon.addEntry(multiTask.build());

        SubCategoryBuilder memory = entryBuilder.startSubCategory(
                Component.translatable("config.tlm_sincerely.memory"));
        memory.setExpanded(true);

        memory.add(entryBuilder.startBooleanToggle(
                        Component.translatable("config.tlm_sincerely.memory.enabled"),
                        MemoryConfig.ENABLED.get())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.tlm_sincerely.memory.enabled.tooltip"))
                .setSaveConsumer(MemoryConfig.ENABLED::set)
                .build());

        memory.add(entryBuilder.startIntField(
                        Component.translatable("config.tlm_sincerely.memory.max_memories"),
                        MemoryConfig.MAX_MEMORIES.get())
                .setDefaultValue(50)
                .setMin(1)
                .setMax(200)
                .setTooltip(Component.translatable("config.tlm_sincerely.memory.max_memories.tooltip"))
                .setSaveConsumer(MemoryConfig.MAX_MEMORIES::set)
                .build());

        memory.add(entryBuilder.startIntField(
                        Component.translatable("config.tlm_sincerely.memory.core_limit"),
                        MemoryConfig.CORE_LIMIT.get())
                .setDefaultValue(10)
                .setMin(0)
                .setMax(50)
                .setTooltip(Component.translatable("config.tlm_sincerely.memory.core_limit.tooltip"))
                .setSaveConsumer(MemoryConfig.CORE_LIMIT::set)
                .build());

        memory.add(entryBuilder.startIntField(
                        Component.translatable("config.tlm_sincerely.memory.preview_length"),
                        MemoryConfig.CONTEXT_PREVIEW_LENGTH.get())
                .setDefaultValue(30)
                .setMin(10)
                .setMax(200)
                .setTooltip(Component.translatable("config.tlm_sincerely.memory.preview_length.tooltip"))
                .setSaveConsumer(MemoryConfig.CONTEXT_PREVIEW_LENGTH::set)
                .build());

        memory.add(entryBuilder.startBooleanToggle(
                        Component.translatable("config.tlm_sincerely.memory.auto_evict"),
                        MemoryConfig.AUTO_EVICT.get())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.tlm_sincerely.memory.auto_evict.tooltip"))
                .setSaveConsumer(MemoryConfig.AUTO_EVICT::set)
                .build());

        memory.add(entryBuilder.startBooleanToggle(
                        Component.translatable("config.tlm_sincerely.memory.guidance"),
                        MemoryConfig.MEMORY_GUIDANCE.get())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.tlm_sincerely.memory.guidance.tooltip"))
                .setSaveConsumer(MemoryConfig.MEMORY_GUIDANCE::set)
                .build());

        memory.add(entryBuilder.startBooleanToggle(
                        Component.translatable("config.tlm_sincerely.memory.tidy_enabled"),
                        MemoryConfig.TIDY_ENABLED.get())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.tlm_sincerely.memory.tidy_enabled.tooltip"))
                .setSaveConsumer(MemoryConfig.TIDY_ENABLED::set)
                .build());

        memory.add(entryBuilder.startDoubleField(
                        Component.translatable("config.tlm_sincerely.memory.tidy_threshold"),
                        MemoryConfig.TIDY_THRESHOLD.get())
                .setDefaultValue(0.8)
                .setMin(0.5)
                .setMax(1.0)
                .setTooltip(Component.translatable("config.tlm_sincerely.memory.tidy_threshold.tooltip"))
                .setSaveConsumer(MemoryConfig.TIDY_THRESHOLD::set)
                .build());

        memory.add(entryBuilder.startIntField(
                        Component.translatable("config.tlm_sincerely.memory.tidy_cooldown"),
                        MemoryConfig.TIDY_COOLDOWN_MINUTES.get())
                .setDefaultValue(20)
                .setMin(1)
                .setMax(1440)
                .setTooltip(Component.translatable("config.tlm_sincerely.memory.tidy_cooldown.tooltip"))
                .setSaveConsumer(MemoryConfig.TIDY_COOLDOWN_MINUTES::set)
                .build());

        memory.add(entryBuilder.startBooleanToggle(
                        Component.translatable("config.tlm_sincerely.memory.show_source"),
                        MemoryConfig.SHOW_SOURCE.get())
                .setDefaultValue(false)
                .setTooltip(Component.translatable("config.tlm_sincerely.memory.show_source.tooltip"))
                .setSaveConsumer(MemoryConfig.SHOW_SOURCE::set)
                .build());

        memory.add(entryBuilder.startStrField(
                        Component.translatable("config.tlm_sincerely.memory.preview_mode"),
                        MemoryConfig.PREVIEW_MODE.get())
                .setDefaultValue("full")
                .setTooltip(Component.translatable("config.tlm_sincerely.memory.preview_mode.tooltip"))
                .setSaveConsumer(v -> {
                    if ("full".equals(v) || "keys_only".equals(v)) {
                        MemoryConfig.PREVIEW_MODE.set(v);
                    } else {
                        MemoryConfig.PREVIEW_MODE.set("full");
                    }
                })
                .build());

        addon.addEntry(memory.build());
    }

    public static void register() {
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (client, parent) -> create().setParentScreen(parent).build()));
    }
}
