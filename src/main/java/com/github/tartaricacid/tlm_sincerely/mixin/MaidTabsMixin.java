package com.github.tartaricacid.tlm_sincerely.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.MaidTabs;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.MaidTabButton;
import com.github.tartaricacid.touhoulittlemaid.network.NetworkHandler;
import com.github.tartaricacid.touhoulittlemaid.network.message.ToggleTabMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

@Mixin(MaidTabs.class)
public class MaidTabsMixin {
    @Inject(method = "getTabs", at = @At("RETURN"), cancellable = true, remap = false)
    private void tlmSincerely$addPriorityTab(AbstractMaidContainerGui<?> screen, CallbackInfoReturnable<MaidTabButton[]> cir) {
        MaidTabButton[] original = cir.getReturnValue();
        MaidTabs self = (MaidTabs) (Object) this;

        int entityId = getPrivateInt(self, "entityId");
        int leftPos = getPrivateInt(self, "leftPos");
        int topPos = getPrivateInt(self, "topPos");

        MaidTabButton priority = new MaidTabButton(leftPos + 169, topPos + 5, 182, "task_priority",
                (b) -> NetworkHandler.CHANNEL.sendToServer(new ToggleTabMessage(entityId, 5)));

        MaidTabButton[] result = new MaidTabButton[original.length + 1];
        System.arraycopy(original, 0, result, 0, original.length);
        result[original.length] = priority;
        cir.setReturnValue(result);
    }

    private static int getPrivateInt(Object obj, String fieldName) {
        try {
            Field field = MaidTabs.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(obj);
        } catch (Exception e) {
            return 0;
        }
    }
}

