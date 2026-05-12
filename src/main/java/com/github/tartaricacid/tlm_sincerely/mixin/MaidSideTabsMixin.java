package com.github.tartaricacid.tlm_sincerely.mixin;

import com.github.tartaricacid.tlm_sincerely.client.gui.TaskPriorityScreen;
import com.github.tartaricacid.tlm_sincerely.client.gui.widget.PrioritySideTabButton;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.MaidSideTabs;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.MaidSideTabButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.List;

@Mixin(MaidSideTabs.class)
public class MaidSideTabsMixin {
    @Inject(method = "getTabs", at = @At("RETURN"), cancellable = true, remap = false)
    private void tlmSincerely$addPriorityTab(AbstractMaidContainerGui<?> screen, CallbackInfoReturnable<MaidSideTabButton[]> cir) {
        MaidSideTabButton[] original = cir.getReturnValue();
        MaidSideTabs self = (MaidSideTabs) (Object) this;

        int rightPos = getPrivateInt(self, "rightPos");
        int topPos = getPrivateInt(self, "topPos");
        int spacing = 25;
        int index = 2;

        MaidSideTabButton priority = new PrioritySideTabButton(
                rightPos, topPos + index * spacing, index * spacing,
                (b) -> {
                    EntityMaid maid = screen.getMaid();
                    if (maid != null) {
                        Minecraft.getInstance().setScreen(new TaskPriorityScreen(maid));
                    }
                },
                List.of(
                        Component.translatable("gui.tlm_sincerely.button.task_priority"),
                        Component.translatable("gui.tlm_sincerely.button.task_priority.desc")
                )
        );

        MaidSideTabButton[] result = new MaidSideTabButton[original.length + 1];
        System.arraycopy(original, 0, result, 0, original.length);
        result[original.length] = priority;
        cir.setReturnValue(result);
    }

    private static int getPrivateInt(Object obj, String fieldName) {
        try {
            Field field = MaidSideTabs.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(obj);
        } catch (Exception e) {
            return 0;
        }
    }
}
