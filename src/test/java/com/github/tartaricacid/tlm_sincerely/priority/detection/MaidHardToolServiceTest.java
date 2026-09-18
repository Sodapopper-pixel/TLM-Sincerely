package com.github.tartaricacid.tlm_sincerely.priority.detection;

import com.github.tartaricacid.tlm_sincerely.priority.detection.HardToolRequirement;
import com.github.tartaricacid.tlm_sincerely.priority.detection.MaidHardToolService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaidHardToolServiceTest {
    @Test
    void register_andGetRequirement() {
        ResourceLocation task = new ResourceLocation("test", "task");
        HardToolRequirement req = new HardToolRequirement("fishing_rod", stack -> stack.is(Items.FISHING_ROD));
        MaidHardToolService.register(task, req);
        assertEquals(req, MaidHardToolService.getRequirement(task));
    }

    @Test
    void reRegister_overwritesPrevious() {
        ResourceLocation task = new ResourceLocation("test", "task");
        HardToolRequirement req1 = new HardToolRequirement("fishing_rod", stack -> stack.is(Items.FISHING_ROD));
        HardToolRequirement req2 = new HardToolRequirement("shears", stack -> stack.is(Items.SHEARS));
        MaidHardToolService.register(task, req1);
        MaidHardToolService.register(task, req2);
        assertEquals(req2, MaidHardToolService.getRequirement(task));
    }
}
