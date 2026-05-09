package com.github.tartaricacid.tlm_sincerely.ai.tool;

import com.github.tartaricacid.tlm_sincerely.priority.TaskPriorityManager;
import com.github.tartaricacid.tlm_sincerely.priority.TaskPriorityPreset;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TaskPriorityTool implements ITool<TaskPriorityTool.Result> {
    private static final String TOOL_ID = "task_priority";

    private static final String TOOL_DESC = """
            Configure task priority ordering for automatic task switching.
            Use 'query' to see the current priority settings.
            Use 'set' to set a task's priority (1-10, lower = higher priority).
            Use 'switch_preset' to switch to a named preset.
            """.trim();

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("action").forGetter(Result::action),
            Codec.STRING.optionalFieldOf("task_id", "").forGetter(Result::taskId),
            Codec.INT.optionalFieldOf("priority", 0).forGetter(Result::priority),
            Codec.STRING.optionalFieldOf("preset_name", "").forGetter(Result::presetName)
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
                .setDescription("The action to perform")
                .addEnumValues("query", "set", "switch_preset");
        root.addProperties("action", action);

        List<IMaidTask> tasks = TaskManager.getTaskIndex();
        StringParameter taskId = StringParameter.create();
        for (IMaidTask task : tasks) {
            taskId.addEnumValues(task.getUid().toString());
        }
        root.addProperties("task_id", taskId, false);

        IntegerParameter priority = IntegerParameter.create();
        root.addProperties("priority", priority, false);

        StringParameter presetName = StringParameter.create();
        TaskPriorityManager.loadPresets();
        for (String name : TaskPriorityManager.getPresetNames()) {
            presetName.addEnumValues(name);
        }
        root.addProperties("preset_name", presetName, false);

        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, Result result, LLMCallback callback) {
        TaskPriorityManager.loadPresets();

        return switch (result.action()) {
            case "query" -> callback.addToolResult(queryPriorities(), toolCallId);
            case "set" -> callback.addToolResult(setPriority(result), toolCallId);
            case "switch_preset" -> callback.addToolResult(switchPreset(result), toolCallId);
            default -> callback.addToolResult(ITool.invalidParam("action",
                    List.of("query", "set", "switch_preset"),
                    "Unknown action: " + result.action()), toolCallId);
        };
    }

    @Override
    public String invocationSummary(Result result) {
        return "%s { %s }".formatted(TOOL_ID, result.action());
    }

    private String queryPriorities() {
        TaskPriorityPreset preset = TaskPriorityManager.getActivePreset();
        if (preset == null || preset.getPriorities().isEmpty()) {
            return "No task priority configuration set. Use 'set' to configure.";
        }

        StringBuilder sb = new StringBuilder("Current task priority (preset: ");
        sb.append(TaskPriorityManager.getActivePresetName()).append("):\n");

        List<ResourceLocation> sorted = preset.getSortedTasks();
        for (int i = 0; i < sorted.size(); i++) {
            ResourceLocation id = sorted.get(i);
            int priority = preset.getPriorities().getOrDefault(id, 0);
            sb.append("  ").append(i + 1).append(". [").append(priority).append("] ").append(id).append("\n");
        }

        List<String> names = TaskPriorityManager.getPresetNames();
        if (names.size() > 1) {
            sb.append("Available presets: ").append(String.join(", ", names));
        }
        return sb.toString();
    }

    private String setPriority(Result result) {
        if (result.taskId().isEmpty()) {
            List<String> values = TaskManager.getTaskIndex().stream()
                    .map(IMaidTask::getUid).map(ResourceLocation::toString).toList();
            return ITool.invalidParam("task_id", values, "task_id is required for 'set' action");
        }
        if (result.priority() < 1 || result.priority() > 10) {
            return "priority must be between 1 and 10";
        }

        ResourceLocation taskId = new ResourceLocation(result.taskId());
        TaskPriorityManager.setTaskPriority(taskId, result.priority());
        return "Set priority of %s to %d".formatted(taskId, result.priority());
    }

    private String switchPreset(Result result) {
        if (result.presetName().isEmpty()) {
            List<String> names = TaskPriorityManager.getPresetNames();
            return ITool.invalidParam("preset_name", names, "preset_name is required for 'switch_preset' action");
        }

        String name = result.presetName();
        List<String> names = TaskPriorityManager.getPresetNames();
        if (!names.contains(name)) {
            return ITool.invalidParam("preset_name", names, "Preset not found: " + name);
        }

        TaskPriorityManager.setActivePreset(name);
        return "Switched to preset: " + name;
    }

    public record Result(String action, String taskId, int priority, String presetName) {
    }
}
