package com.github.tartaricacid.tlm_sincerely.ai.context;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPreset;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPresetService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkState;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkStateService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.compat.AutoWorkCompatService;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.AbstractMaidContext;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.List;

/** Appends a prose snapshot of the maid's current auto-work state to AI context. */
public final class MaidAutoWorkContext extends AbstractMaidContext {
    public MaidAutoWorkContext() {
        super("tlm_sincerely_auto_work_status", "Current auto work switch status and configured task priority");
    }

    @Override
    public String getValue(EntityMaid maid) {
        MinecraftServer server = maid == null ? null : maid.level().getServer();
        AutoWorkPresetService presetService = AutoWorkPresetService.getOrNull(server);
        AutoWorkStateService stateService = AutoWorkStateService.getOrNull(server);
        if (presetService == null || stateService == null) {
            return "Auto work status is unavailable because its server services are not ready.";
        }

        AutoWorkState state = stateService.getState(maid);
        AutoWorkPreset preset = presetService.resolveForMaid(state);
        AutoWorkCompatService compatService = AutoWorkCompatService.getOrNull(server);
        String globalScheduling = PriorityConfig.ENABLED.get()
                ? "Global automatic scheduling is active. "
                : "Global automatic scheduling is paused, so no automatic task switch will occur until it is enabled. ";
        if (preset == null) {
            return globalScheduling + "Auto work is %s, but no effective preset is available.".formatted(
                    state.enabled() ? "enabled" : "disabled");
        }

        StringBuilder out = new StringBuilder(globalScheduling).append("Auto work is ")
                .append(state.enabled() ? "enabled" : "disabled")
                .append(". The effective preset is '").append(preset.getName())
                .append("' (preset_id=").append(preset.getId()).append("). ")
                .append("Configured task priority: ");
        appendOrder(out, preset.getOrder(), compatService);
        return out.toString();
    }

    private static void appendOrder(StringBuilder out, List<ResourceLocation> order,
                                    AutoWorkCompatService compatService) {
        if (order.isEmpty()) {
            out.append("none.");
            return;
        }
        for (int index = 0; index < order.size(); index++) {
            if (index > 0) {
                out.append("; ");
            }
            ResourceLocation taskId = order.get(index);
            String name = TaskManager.findTask(taskId)
                    .map(task -> task.getName().getString())
                    .orElse("unregistered task");
            out.append(index + 1).append(") ").append(name).append(" [").append(taskId).append("]");
            AutoWorkCompatService.ReportEntry entry = compatService == null ? null : compatService.getEntry(taskId);
            if (entry != null) {
                out.append(" {detector=").append(entry.level()).append(", reason=")
                        .append(entry.reason()).append('}');
            }
        }
        out.append('.');
    }
}
