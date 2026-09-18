package com.github.tartaricacid.tlm_sincerely;

import com.github.tartaricacid.tlm_sincerely.ai.context.MaidMemoryContext;
import com.github.tartaricacid.tlm_sincerely.ai.context.MaidAutoWorkContext;
import com.github.tartaricacid.tlm_sincerely.ai.tool.AutoWorkTool;
import com.github.tartaricacid.tlm_sincerely.ai.tool.MaidCommandTool;
import com.github.tartaricacid.tlm_sincerely.ai.tool.MaidMemoryTool;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkTaskDataKeys;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.GameContextRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister;
import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.data.TaskDataRegister;

@LittleMaidExtension
public class SincerelyExtension implements ILittleMaid {
    public static final String MOD_ID = "tlm_sincerely";

    @Override
    public void registerAITool(ToolRegister register) {
        register.register(new AutoWorkTool());
        register.register(new MaidMemoryTool());
        register.register(new MaidCommandTool());
    }

    @Override
    public void registerTaskData(TaskDataRegister register) {
        register.register(AutoWorkTaskDataKeys.STATE_KEY);
    }

    @Override
    public void registerAIMaidContext(GameContextRegister register) {
        register.registerCategory("tlm_sincerely_memory",
                "Maid persistent memories: key-value facts about the player, past events, preferences",
                true);
        register.registerContext("tlm_sincerely_memory", new MaidMemoryContext());
        register.registerCategory("tlm_sincerely_auto_work",
                "Current auto work switch status and configured task priority",
                true);
        register.registerContext("tlm_sincerely_auto_work", new MaidAutoWorkContext());
    }
}
