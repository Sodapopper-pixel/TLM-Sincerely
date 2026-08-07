package com.github.tartaricacid.tlm_sincerely.ai.tool;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.MemoryConfig;
import com.github.tartaricacid.tlm_sincerely.memory.MaidMemory;
import com.github.tartaricacid.tlm_sincerely.memory.MaidMemoryManager;
import com.github.tartaricacid.tlm_sincerely.memory.MaidMemory.MemoryEntry;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Optional;

public class MaidMemoryTool implements ITool<MaidMemoryTool.Result> {
    private static final String TOOL_ID = "tlm_memory";

    private static final String TOOL_DESC = """
            Manage persistent key-value memories for the maid.
            Use 'remember' to store important facts about the player (preferences, history, promises).
            Use 'recall' to retrieve the full content of a specific memory.
            Use 'forget' to delete a memory when the player asks or when it's no longer relevant.
            """.trim();

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("action").forGetter(Result::action),
            Codec.STRING.optionalFieldOf("key", "").forGetter(Result::key),
            Codec.STRING.optionalFieldOf("value", "").forGetter(Result::value),
            Codec.STRING.optionalFieldOf("importance", MemoryEntry.ARCHIVE).forGetter(Result::importance)
    ).apply(instance, Result::new));

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return TOOL_DESC;
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        StringParameter action = StringParameter.create()
                .setDescription("The action to perform on memories")
                .addEnumValues("remember", "recall", "forget");
        root.addProperties("action", action);

        StringParameter key = StringParameter.create()
                .setDescription("The memory key (unique identifier for the memory). For 'remember' this may be a new key; for 'recall'/'forget' use an existing key (see preview or 'Available keys' in tool errors).");
        root.addProperties("key", key, false);

        StringParameter value = StringParameter.create()
                .setDescription("The memory value. Required for 'remember' action.");
        root.addProperties("value", value, false);

        StringParameter importance = StringParameter.create()
                .setDescription("Memory importance: 'core' (always shown in full) or 'archive' (shown as preview)")
                .addEnumValues(MemoryEntry.CORE, MemoryEntry.ARCHIVE);
        root.addProperties("importance", importance, false);

        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, Result result, LLMCallback callback) {
        if (!MemoryConfig.ENABLED.get()) {
            return callback.addToolResult("Memory system is currently disabled.", toolCallId);
        }

        EntityMaid maid = callback.getMaid();
        MaidMemory memory = MaidMemoryManager.load(maid.getUUID());

        return switch (result.action()) {
            case "remember" -> callback.addToolResult(remember(memory, maid, result), toolCallId);
            case "recall" -> callback.addToolResult(recall(memory, result), toolCallId);
            case "forget" -> callback.addToolResult(forget(memory, maid, result), toolCallId);
            default -> callback.addToolResult(ITool.invalidParam("action",
                    List.of("remember", "recall", "forget"),
                    "Unknown action: " + result.action()), toolCallId);
        };
    }

    @Override
    public String invocationSummary(Result result) {
        return TOOL_ID + " { " + result.action() + " " + result.key() + " }";
    }

    private String remember(MaidMemory memory, EntityMaid maid, Result result) {
        if (result.key().isEmpty()) {
            return ITool.invalidParam("key", List.of("<memory key>"),
                    "key is required for 'remember' action");
        }
        if (result.value().isEmpty()) {
            return ITool.invalidParam("value", List.of("<memory value>"),
                    "value is required for 'remember' action");
        }

        int max = MemoryConfig.MAX_MEMORIES.get();
        String trimmedKey = result.key().trim();
        if (memory.size() >= max && !memory.getMemories().containsKey(trimmedKey)) {
            return "Memory limit reached (%d max). Delete some memories first with 'forget'.".formatted(max);
        }

        String importance = result.importance();
        memory.set(result.key(), result.value(), importance);
        MaidMemoryManager.save(maid.getUUID(), memory);
        return "Remembered '%s' = \"%s\" (%s)".formatted(result.key(), result.value(), importance);
    }

    private String recall(MaidMemory memory, Result result) {
        if (result.key().isEmpty()) {
            List<String> keys = memory.keys();
            return ITool.invalidParam("key", keys.isEmpty() ? List.of("no memories stored") : keys,
                    "key is required for 'recall' action");
        }

        Optional<MemoryEntry> entry = memory.get(result.key());
        if (entry.isEmpty()) {
            return "Memory key '%s' not found. Available keys: %s".formatted(
                    result.key(), memory.keys());
        }

        MemoryEntry mem = entry.get();
        return "[%s] %s: %s".formatted(mem.importance(), result.key(), mem.value());
    }

    private String forget(MaidMemory memory, EntityMaid maid, Result result) {
        if (result.key().isEmpty()) {
            List<String> keys = memory.keys();
            return ITool.invalidParam("key", keys.isEmpty() ? List.of("no memories stored") : keys,
                    "key is required for 'forget' action");
        }

        if (!memory.getMemories().containsKey(result.key())) {
            return "Memory key '%s' not found. Available keys: %s".formatted(
                    result.key(), memory.keys());
        }

        memory.forget(result.key());
        MaidMemoryManager.save(maid.getUUID(), memory);
        return "Forgot memory '%s'".formatted(result.key());
    }

    public record Result(String action, String key, String value, String importance) {
    }
}
