package com.github.tartaricacid.tlm_sincerely.ai.context;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
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
        super("tlm_sincerely_auto_work_status", "Current auto work switch status and bound task order");
    }

    @Override
    public String getValue(EntityMaid maid) {
        MinecraftServer server = maid == null ? null : maid.level().getServer();
        AutoWorkStateService stateService = AutoWorkStateService.getOrNull(server);
        if (stateService == null) {
            return "Auto work status is unavailable because its server services are not ready.";
        }

        AutoWorkState state = stateService.getState(maid);
        AutoWorkCompatService compatService = AutoWorkCompatService.getOrNull(server);
        String globalScheduling = PriorityConfig.ENABLED.get()
                ? "Global automatic scheduling is active. "
                : "Global automatic scheduling is paused, so no automatic task switch will occur until it is enabled. ";
        if (!state.enabled()) {
            return globalScheduling + "Auto work is disabled for this maid.";
        }

        StringBuilder out = new StringBuilder(globalScheduling)
                .append("Auto work is enabled. ");
        if (state.presetName().isEmpty()) {
            out.append("No preset is bound yet. ");
        } else {
            out.append("The bound preset is '").append(state.presetName())
                    .append("' (preset_id=").append(state.presetId()).append("). ");
        }
        out.append("Bound task order: ");
        appendOrder(out, state.order(), compatService);
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
