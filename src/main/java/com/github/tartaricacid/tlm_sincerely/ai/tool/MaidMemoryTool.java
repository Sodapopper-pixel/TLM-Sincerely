package com.github.tartaricacid.tlm_sincerely.ai.tool;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.MemoryConfig;
import com.github.tartaricacid.tlm_sincerely.memory.MaidMemory;
import com.github.tartaricacid.tlm_sincerely.memory.MaidMemoryManager;
import com.github.tartaricacid.tlm_sincerely.memory.MaidMemory.MemoryEntry;
import com.github.tartaricacid.tlm_sincerely.memory.MemoryMaintenanceManager;
import com.github.tartaricacid.tlm_sincerely.memory.MemoryChatTracker;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ArrayParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class MaidMemoryTool implements ITool<MaidMemoryTool.Result> {
    private static final String TOOL_ID = "tlm_memory";

    private static final Set<String> MAINTENANCE_ACTIONS = Set.of("search", "recall", "merge");

    private static final String TOOL_DESC = """
            Manage persistent key-value memories for the maid.
            Use 'remember' to store important facts about the player (preferences, history, promises).
            Use 'recall' to retrieve the full content of a specific memory.
            Use 'forget' to delete a memory when the player asks or when it's no longer relevant.
            Use 'search' to find memories by keyword (matches key or value, returns up to 10 results).
            Use 'merge' to combine 2-5 similar archive entries into one.
            """.trim();

    private static final String MAINTENANCE_MSG =
            "Only search/recall/merge are allowed during memory maintenance.";

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("action").forGetter(Result::action),
            Codec.STRING.optionalFieldOf("key", "").forGetter(Result::key),
            Codec.STRING.optionalFieldOf("value", "").forGetter(Result::value),
            Codec.STRING.optionalFieldOf("importance", MemoryEntry.ARCHIVE).forGetter(Result::importance),
            Codec.STRING.optionalFieldOf("query", "").forGetter(Result::query),
            Codec.list(Codec.STRING).optionalFieldOf("keys", List.of()).forGetter(Result::keys)
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
                .addEnumValues("remember", "recall", "forget", "search", "merge");
        root.addProperties("action", action);

        StringParameter key = StringParameter.create()
                .setDescription("The memory key (unique identifier for the memory). For 'remember' this may be a new key; for 'recall'/'forget' use an existing key (see preview or 'Available keys' in tool errors). For 'merge' this is the target key.");
        root.addProperties("key", key, false);

        StringParameter value = StringParameter.create()
                .setDescription("The memory value. Required for 'remember' and 'merge' actions.");
        root.addProperties("value", value, false);

        StringParameter importance = StringParameter.create()
                .setDescription("Memory importance: 'core' (always shown in full) or 'archive' (shown as preview)")
                .addEnumValues(MemoryEntry.CORE, MemoryEntry.ARCHIVE);
        root.addProperties("importance", importance, false);

        StringParameter query = StringParameter.create()
                .setDescription("Search query for 'search' action. Returns matching memory keys and previews (up to 10).");
        root.addProperties("query", query, false);

        ArrayParameter keys = ArrayParameter.create()
                .setDescription("Source keys to merge (2-5 existing archive entries) for 'merge' action.")
                .setItems(StringParameter.create())
                .setRange(2, 5)
                .setUniqueItems(true);
        root.addProperties("keys", keys, false);

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

        boolean maintenanceCallback = MemoryChatTracker.isMaintenance(callback);
        if (maintenanceCallback && !MemoryMaintenanceManager.isMaintaining(maid.getUUID())) {
            return callback.addToolResult("Memory maintenance request expired; no changes were applied.", toolCallId);
        }
        if (MemoryMaintenanceManager.isMaintaining(maid.getUUID())) {
            MemoryMaintenanceManager.recordToolActivity(maid.getUUID());
            if (!maintenanceCallback) {
                return callback.addToolResult(MAINTENANCE_MSG, toolCallId);
            }
        }

        MaidMemory memory = MaidMemoryManager.load(maid.getUUID());

        return switch (result.action()) {
            case "remember" -> {
                String msg = remember(memory, maid, result);
                if (MemoryConfig.TIDY_ENABLED.get() && msg.startsWith("Remembered")) {
                    MemoryMaintenanceManager.checkAndScheduleTidy(maid);
                }
                yield callback.addToolResult(msg, toolCallId);
            }
            case "recall" -> callback.addToolResult(recall(memory, maid, result), toolCallId);
            case "forget" -> callback.addToolResult(forget(memory, maid, result), toolCallId);
            case "search" -> callback.addToolResult(search(memory, result), toolCallId);
            case "merge" -> callback.addToolResult(merge(memory, maid, result), toolCallId);
            default -> callback.addToolResult(ITool.invalidParam("action",
                    List.of("remember", "recall", "forget", "search", "merge"),
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
            if (!MemoryConfig.AUTO_EVICT.get()) {
                return "Memory limit reached (%d max). Delete some memories first with 'forget'.".formatted(max);
            }
            Optional<String> oldestArchive = memory.findOldestArchiveKey();
            if (oldestArchive.isEmpty()) {
                return ("Memory limit reached (%d max) and all entries are core. "
                        + "Ask the player which memory to forget.").formatted(max);
            }
            String evictedKey = oldestArchive.get();
            memory.forget(evictedKey);
            memory.set(result.key(), result.value(), result.importance(), getSource(maid));
            if (!MaidMemoryManager.save(maid.getUUID(), memory)) {
                return "Memory save failed after eviction. Please try again.";
            }
            return "Remembered '%s' = \"%s\" (%s). Evicted oldest archive '%s' to make room.".formatted(
                    result.key(), result.value(), result.importance(), evictedKey);
        }

        memory.set(result.key(), result.value(), result.importance(), getSource(maid));
        if (!MaidMemoryManager.save(maid.getUUID(), memory)) {
            return "Memory save failed. Please try again.";
        }
        return "Remembered '%s' = \"%s\" (%s)".formatted(result.key(), result.value(), result.importance());
    }

    private String recall(MaidMemory memory, EntityMaid maid, Result result) {
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

        memory.touch(result.key());
        if (!MaidMemoryManager.save(maid.getUUID(), memory)) {
            return "Memory touch save failed. Please try again.";
        }

        MemoryEntry mem = entry.get();
        String output = "[%s] %s: %s".formatted(mem.importance(), result.key(), mem.value());
        if (MemoryConfig.SHOW_SOURCE.get() && !mem.source().isEmpty()) {
            output += " (source: " + mem.source() + ")";
        }
        return output;
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
        if (!MaidMemoryManager.save(maid.getUUID(), memory)) {
            return "Memory forget save failed. Please try again.";
        }
        return "Forgot memory '%s'".formatted(result.key());
    }

    private String search(MaidMemory memory, Result result) {
        String query = result.query().trim();
        if (query.isEmpty()) {
            return ITool.invalidParam("query", List.of("<search query>"),
                    "query is required for 'search' action");
        }

        List<Map.Entry<String, MemoryEntry>> matches = memory.search(query);
        if (matches.isEmpty()) {
            return "No memories match '%s'.".formatted(query);
        }

        int previewLength = MemoryConfig.CONTEXT_PREVIEW_LENGTH.get();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, MemoryEntry> entry : matches) {
            String value = entry.getValue().value();
            String preview = value.length() > previewLength
                    ? value.substring(0, previewLength) + "..."
                    : value;
            sb.append("  %s: \"%s\"\n".formatted(entry.getKey(), preview));
        }
        sb.append("%d matches".formatted(matches.size()));
        return sb.toString();
    }

    private String merge(MaidMemory memory, EntityMaid maid, Result result) {
        List<String> keys = result.keys();
        if (keys == null || keys.size() < 2 || keys.size() > 5) {
            return ITool.invalidParam("keys", List.of("2-5 source keys"),
                    "merge requires 2-5 source keys");
        }
        if (result.key().isEmpty()) {
            return ITool.invalidParam("key", List.of("<target key>"),
                    "target key is required for 'merge' action");
        }
        if (result.value().isEmpty()) {
            return ITool.invalidParam("value", List.of("<merged value>"),
                    "merged value is required for 'merge' action");
        }

        List<String> trimmedKeys = new ArrayList<>();
        for (String k : keys) {
            String tk = k.trim();
            if (!memory.getMemories().containsKey(tk)) {
                return "Source key '%s' not found.".formatted(tk);
            }
            MemoryEntry entry = memory.getMemories().get(tk);
            if (!MemoryEntry.ARCHIVE.equals(entry.importance())) {
                return "merge can only combine existing archive memories; core entries are protected.";
            }
            if (trimmedKeys.contains(tk)) {
                return "Duplicate source key '%s'.".formatted(tk);
            }
            trimmedKeys.add(tk);
        }

        String targetKey = result.key().trim();
        MemoryEntry existingTarget = memory.getMemories().get(targetKey);
        if (existingTarget != null && MemoryEntry.CORE.equals(existingTarget.importance())) {
            return "Target key '%s' is a core entry and cannot be overwritten by merge.".formatted(targetKey);
        }

        for (String tk : trimmedKeys) {
            memory.forget(tk);
        }
        memory.set(targetKey, result.value(), MemoryEntry.ARCHIVE, getSource(maid));
        if (!MaidMemoryManager.save(maid.getUUID(), memory)) {
            return "Memory merge save failed. Please try again.";
        }

        MemoryMaintenanceManager.logMerge(maid.getUUID(), trimmedKeys, targetKey);

        return "Merged %d entries into '%s'".formatted(trimmedKeys.size(), targetKey);
    }

    private String getSource(EntityMaid maid) {
        LivingEntity owner = maid.getOwner();
        if (owner != null) {
            return owner.getName().getString();
        }
        return "";
    }

    public record Result(String action, String key, String value, String importance,
                         String query, List<String> keys) {
    }
}
