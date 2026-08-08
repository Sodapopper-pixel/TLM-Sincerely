package com.github.tartaricacid.tlm_sincerely.ai.tool;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPreset;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPresetService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkState;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkStateService;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.BoolParameter;
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
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Server-authoritative AI tool for the auto work switch (T-2 C2).
 *
 * <p>Replaces the legacy {@code task_priority} tool. There is no longer any
 * global active preset or numeric priority: the preset library is a
 * per-server resource addressed by UUID, and the maid's ordering is the
 * list of task UIDs in a preset, where index 0 is the highest priority.
 *
 * <p>Each call resolves the bound {@link MinecraftServer} from the maid in
 * the current {@link LLMCallback} and goes through
 * {@link AutoWorkPresetService} / {@link AutoWorkStateService}. All write
 * operations on the preset library are persisted via
 * {@link AutoWorkPresetService#persistNow()}; per-maid state changes are
 * routed through {@link AutoWorkStateService#setState}.
 *
 * <p>Legacy {@code set} / {@code switch_preset} actions are explicitly
 * rejected with a deprecation notice so older LLM contexts that still
 * mention 1-10 numeric priorities or named preset switches are forced
 * to migrate.
 */
public class AutoWorkTool implements ITool<AutoWorkTool.Result> {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoWorkTool.class);
    private static final String TOOL_ID = "auto_work";

    private static final String TOOL_DESC = """
            Manage the per-maid auto work switch and the server-wide preset library.
            Use 'query' to see this maid's auto work state and the available presets.
            Use 'set_auto_enabled' to enable/disable the auto work switch for this maid (preset_id is required to enable).
            Use 'select_preset' to bind this maid to a specific preset (by preset_id).
            Use 'create_preset' / 'rename_preset' / 'delete_preset' to manage presets in the shared library (by preset_id).
            Use 'add_task' / 'remove_task' / 'move_task' to edit a preset's ordered task list; 'move_task' uses a 1-based position.
            The preset library is a server-wide shared resource: preset and task edits affect every maid that uses them.
            """.trim();

    /**
     * Action names accepted by the tool. Kept in one place so the schema
     * enum and the dispatch switch cannot drift apart.
     */
    private static final List<String> ACTIONS = List.of(
            "query",
            "set_auto_enabled",
            "select_preset",
            "create_preset",
            "rename_preset",
            "delete_preset",
            "add_task",
            "remove_task",
            "move_task"
    );

    private static final String DEPRECATED_NUMERIC_MSG =
            "The 'set' action with a 1-10 numeric priority is no longer supported. "
                    + "Use 'add_task' / 'remove_task' / 'move_task' to manage a preset's task order, "
                    + "or call 'query' to inspect the current auto work state.";

    private static final String DEPRECATED_NAMED_PRESET_MSG =
            "The 'switch_preset' action with a preset name is no longer supported. "
                    + "Presets are addressed by UUID; call 'query' to list preset_id values, "
                    + "then use 'select_preset' with the chosen preset_id.";

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("action").forGetter(Result::action),
            Codec.BOOL.optionalFieldOf("enabled", false).forGetter(Result::enabled),
            Codec.STRING.optionalFieldOf("preset_id", "").forGetter(Result::presetId),
            Codec.STRING.optionalFieldOf("name", "").forGetter(Result::name),
            Codec.STRING.optionalFieldOf("task_id", "").forGetter(Result::taskId),
            Codec.INT.optionalFieldOf("position", 0).forGetter(Result::position)
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
                .addEnumValues(ACTIONS.toArray(new String[0]));
        root.addProperties("action", action);

        BoolParameter enabled = BoolParameter.create();
        enabled.setDescription("For 'set_auto_enabled': true to enable, false to disable.");
        root.addProperties("enabled", enabled, false);

        StringParameter presetId = StringParameter.create()
                .setDescription("Preset UUID. For 'select_preset' / 'rename_preset' / 'delete_preset' / 'add_task' / 'remove_task' / 'move_task' this identifies the target preset. For 'set_auto_enabled' this is the preset to bind the maid to when enabling. Obtain UUIDs from 'query'.");
        root.addProperties("preset_id", presetId, false);

        StringParameter name = StringParameter.create()
                .setDescription("Human-readable name. Required for 'create_preset' (new preset) and 'rename_preset' (new name).")
                .setMinLength(1)
                .setMaxLength(64);
        root.addProperties("name", name, false);

        // task_id is static (IMaidTask registration is process-wide) so
        // enumerating here is safe and helps the LLM avoid typos.
        StringParameter taskId = StringParameter.create()
                .setDescription("Task UID (e.g. 'touhou_little_maid:attack'). Required for 'add_task' / 'remove_task' / 'move_task'.");
        for (IMaidTask task : TaskManager.getTaskIndex()) {
            taskId.addEnumValues(task.getUid().toString());
        }
        root.addProperties("task_id", taskId, false);

        IntegerParameter position = IntegerParameter.create()
                .setDescription("1-based target position for 'move_task'. Position 1 means the task becomes the highest priority in the preset. Values outside [1, list size] are clamped.");
        root.addProperties("position", position, false);

        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, Result result, LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        MinecraftServer server = maid == null ? null : maid.level().getServer();

        if (maid == null) {
            return callback.addToolResult("No maid context available for this tool call.", toolCallId);
        }
        if (server == null) {
            return callback.addToolResult("Auto work services are not available on the client; "
                    + "this tool must run on the server thread with a live MinecraftServer.", toolCallId);
        }
        AutoWorkPresetService presetService = AutoWorkPresetService.getOrNull(server);
        AutoWorkStateService stateService = AutoWorkStateService.getOrNull(server);
        if (presetService == null || stateService == null) {
            return callback.addToolResult("Auto work services are not bound to this server yet.", toolCallId);
        }

        String action = result.action();
        // Explicit deprecation: do not silently fall back to the new
        // semantics for the legacy actions. Force the LLM to update.
        if ("set".equals(action)) {
            return callback.addToolResult(DEPRECATED_NUMERIC_MSG, toolCallId);
        }
        if ("switch_preset".equals(action)) {
            return callback.addToolResult(DEPRECATED_NAMED_PRESET_MSG, toolCallId);
        }

        return switch (action) {
            case "query" -> callback.addToolResult(query(presetService, stateService, maid), toolCallId);
            case "set_auto_enabled" -> callback.addToolResult(
                    setAutoEnabled(presetService, stateService, maid, result), toolCallId);
            case "select_preset" -> callback.addToolResult(
                    selectPreset(presetService, stateService, maid, result), toolCallId);
            case "create_preset" -> callback.addToolResult(
                    createPreset(presetService, result), toolCallId);
            case "rename_preset" -> callback.addToolResult(
                    renamePreset(presetService, result), toolCallId);
            case "delete_preset" -> callback.addToolResult(
                    deletePreset(presetService, result), toolCallId);
            case "add_task" -> callback.addToolResult(
                    addTask(presetService, result), toolCallId);
            case "remove_task" -> callback.addToolResult(
                    removeTask(presetService, result), toolCallId);
            case "move_task" -> callback.addToolResult(
                    moveTask(presetService, result), toolCallId);
            default -> callback.addToolResult(ITool.invalidParam("action", ACTIONS,
                    "Unknown action: " + action), toolCallId);
        };
    }

    @Override
    public String invocationSummary(Result result) {
        return "%s { %s }".formatted(TOOL_ID, result.action());
    }

    // -----------------------------------------------------------------
    // Action implementations
    // -----------------------------------------------------------------

    private String query(AutoWorkPresetService presetService,
                         AutoWorkStateService stateService,
                         EntityMaid maid) {
        AutoWorkState state = stateService.getState(maid);
        AutoWorkPreset effective = presetService.resolveForMaid(state);
        AutoWorkPreset stored = presetService.getPreset(state.presetId());

        StringBuilder sb = new StringBuilder();
        sb.append("Auto work state for this maid:\n");
        sb.append("  enabled: ").append(state.enabled()).append('\n');
        sb.append("  stored preset_id: ").append(state.presetId())
                .append(stored == null ? " (missing, falling back to default)" : "").append('\n');
        if (effective != null) {
            sb.append("  effective preset: ").append(effective.getName())
                    .append(" (").append(effective.getId()).append(")\n");
            appendOrder(sb, effective);
        } else {
            sb.append("  effective preset: <none>\n");
        }

        sb.append("\nServer preset library:\n");
        for (AutoWorkPreset preset : presetService.listPresets()) {
            sb.append("  - ").append(preset.getName())
                    .append("  preset_id=").append(preset.getId())
                    .append(preset.getId().equals(presetService.getDefaultPresetId()) ? "  (default)" : "")
                    .append("  size=").append(preset.getOrder().size())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    private String setAutoEnabled(AutoWorkPresetService presetService,
                                  AutoWorkStateService stateService,
                                  EntityMaid maid,
                                  Result result) {
        boolean desired = result.enabled();
        if (desired) {
            // When enabling, the maid must have a valid preset to bind to.
            UUID presetId = parsePresetId(result.presetId());
            if (presetId == null) {
                return ITool.invalidParam("preset_id", presetIdValues(presetService),
                        "preset_id is required to enable auto work for a maid");
            }
            AutoWorkPreset preset = presetService.getPreset(presetId);
            if (preset == null) {
                return ITool.invalidParam("preset_id", presetIdValues(presetService),
                        "Preset not found: " + presetId);
            }
            AutoWorkState current = stateService.getState(maid);
            AutoWorkState next = current.withEnabled(true).withPresetId(presetId);
            stateService.setState(maid, next);
            return "Auto work enabled for this maid using preset '%s' (preset_id=%s).".formatted(
                    preset.getName(), presetId);
        }
        // Disabling: preset is irrelevant.
        stateService.setEnabled(maid, false);
        return "Auto work disabled for this maid.";
    }

    private String selectPreset(AutoWorkPresetService presetService,
                                AutoWorkStateService stateService,
                                EntityMaid maid,
                                Result result) {
        UUID presetId = parsePresetId(result.presetId());
        if (presetId == null) {
            return ITool.invalidParam("preset_id", presetIdValues(presetService),
                    "preset_id is required for 'select_preset'");
        }
        AutoWorkPreset preset = presetService.getPreset(presetId);
        if (preset == null) {
            return ITool.invalidParam("preset_id", presetIdValues(presetService),
                    "Preset not found: " + presetId);
        }
        stateService.setPresetId(maid, presetId);
        return "Selected preset '%s' (preset_id=%s) for this maid.".formatted(preset.getName(), presetId);
    }

    private String createPreset(AutoWorkPresetService presetService, Result result) {
        String name = result.name().trim();
        if (name.isEmpty()) {
            return ITool.invalidParam("name", List.of("<preset name>"),
                    "name is required for 'create_preset'");
        }
        AutoWorkPreset preset = presetService.createPreset(name);
        presetService.persistNow();
        LOGGER.info("[AutoWorkTool] create_preset '{}' -> {}", name, preset.getId());
        return "Created preset '%s' with preset_id=%s.".formatted(preset.getName(), preset.getId());
    }

    private String renamePreset(AutoWorkPresetService presetService, Result result) {
        UUID presetId = parsePresetId(result.presetId());
        if (presetId == null) {
            return ITool.invalidParam("preset_id", presetIdValues(presetService),
                    "preset_id is required for 'rename_preset'");
        }
        String newName = result.name().trim();
        if (newName.isEmpty()) {
            return ITool.invalidParam("name", List.of("<new preset name>"),
                    "name is required for 'rename_preset'");
        }
        boolean ok = presetService.renamePreset(presetId, newName);
        if (!ok) {
            return ITool.invalidParam("preset_id", presetIdValues(presetService),
                    "Preset not found: " + presetId);
        }
        presetService.persistNow();
        return "Renamed preset %s to '%s'.".formatted(presetId, newName);
    }

    private String deletePreset(AutoWorkPresetService presetService, Result result) {
        UUID presetId = parsePresetId(result.presetId());
        if (presetId == null) {
            return ITool.invalidParam("preset_id", presetIdValues(presetService),
                    "preset_id is required for 'delete_preset'");
        }
        if (presetService.listPresets().size() <= 1) {
            return "Refused: at least one preset must remain in the library.";
        }
        boolean ok = presetService.deletePreset(presetId);
        if (!ok) {
            return ITool.invalidParam("preset_id", presetIdValues(presetService),
                    "Preset not found: " + presetId);
        }
        presetService.persistNow();
        return "Deleted preset %s. Maids that referenced it fall back to the default preset on next read.".formatted(presetId);
    }

    private String addTask(AutoWorkPresetService presetService, Result result) {
        UUID presetId = parsePresetId(result.presetId());
        if (presetId == null) {
            return ITool.invalidParam("preset_id", presetIdValues(presetService),
                    "preset_id is required for 'add_task'");
        }
        ResourceLocation taskId = parseTaskId(result.taskId());
        if (taskId == null) {
            return ITool.invalidParam("task_id", taskIdValues(),
                    "task_id is required for 'add_task'");
        }
        boolean added = presetService.addTask(presetId, taskId);
        if (!added) {
            // Distinguish 'unknown preset' from 'already in order'.
            if (presetService.getPreset(presetId) == null) {
                return ITool.invalidParam("preset_id", presetIdValues(presetService),
                        "Preset not found: " + presetId);
            }
            return "Task '%s' is already present in preset %s.".formatted(taskId, presetId);
        }
        presetService.persistNow();
        return "Appended task '%s' to preset %s.".formatted(taskId, presetId);
    }

    private String removeTask(AutoWorkPresetService presetService, Result result) {
        UUID presetId = parsePresetId(result.presetId());
        if (presetId == null) {
            return ITool.invalidParam("preset_id", presetIdValues(presetService),
                    "preset_id is required for 'remove_task'");
        }
        ResourceLocation taskId = parseTaskId(result.taskId());
        if (taskId == null) {
            return ITool.invalidParam("task_id", taskIdValues(),
                    "task_id is required for 'remove_task'");
        }
        if (presetService.getPreset(presetId) == null) {
            return ITool.invalidParam("preset_id", presetIdValues(presetService),
                    "Preset not found: " + presetId);
        }
        boolean removed = presetService.removeTask(presetId, taskId);
        if (!removed) {
            return "Task '%s' is not in preset %s.".formatted(taskId, presetId);
        }
        presetService.persistNow();
        return "Removed task '%s' from preset %s.".formatted(taskId, presetId);
    }

    private String moveTask(AutoWorkPresetService presetService, Result result) {
        UUID presetId = parsePresetId(result.presetId());
        if (presetId == null) {
            return ITool.invalidParam("preset_id", presetIdValues(presetService),
                    "preset_id is required for 'move_task'");
        }
        ResourceLocation taskId = parseTaskId(result.taskId());
        if (taskId == null) {
            return ITool.invalidParam("task_id", taskIdValues(),
                    "task_id is required for 'move_task'");
        }
        AutoWorkPreset preset = presetService.getPreset(presetId);
        if (preset == null) {
            return ITool.invalidParam("preset_id", presetIdValues(presetService),
                    "Preset not found: " + presetId);
        }
        if (!preset.hasTask(taskId)) {
            return "Task '%s' is not in preset %s; use 'add_task' first.".formatted(taskId, presetId);
        }
        int orderSize = preset.getOrder().size();
        if (result.position() < 1) {
            return ITool.invalidParam("position", List.of("1..%d".formatted(orderSize)),
                    "position must be 1 or greater (1-based)");
        }
        int targetIndex = result.position() - 1;
        int clamped = Math.max(0, Math.min(targetIndex, orderSize - 1));
        boolean moved = presetService.moveTask(presetId, taskId, clamped);
        if (!moved) {
            return "Task '%s' is already at position %d in preset %s.".formatted(taskId, clamped + 1, presetId);
        }
        presetService.persistNow();
        return "Moved task '%s' to position %d in preset %s.".formatted(taskId, clamped + 1, presetId);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static void appendOrder(StringBuilder sb, AutoWorkPreset preset) {
        List<ResourceLocation> order = preset.getOrder();
        if (order.isEmpty()) {
            sb.append("    (empty)\n");
            return;
        }
        for (int i = 0; i < order.size(); i++) {
            sb.append("    ").append(i + 1).append(". ").append(order.get(i)).append('\n');
        }
    }

    private static List<String> presetIdValues(AutoWorkPresetService presetService) {
        return presetService.listPresets().stream()
                .map(p -> p.getId().toString())
                .collect(Collectors.toList());
    }

    private static List<String> taskIdValues() {
        List<String> out = new ArrayList<>();
        for (IMaidTask task : TaskManager.getTaskIndex()) {
            out.add(task.getUid().toString());
        }
        return out;
    }

    private static UUID parsePresetId(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ResourceLocation parseTaskId(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return new ResourceLocation(raw.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    public record Result(String action,
                         boolean enabled,
                         String presetId,
                         String name,
                         String taskId,
                         int position) {
    }
}
