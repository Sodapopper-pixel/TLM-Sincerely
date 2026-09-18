package com.github.tartaricacid.tlm_sincerely.mixin;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkNetworking;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.SetMaidAutoWorkC2SPacket;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.ScheduleButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.gui.GuiGraphics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the TLM maid container GUI so a regular task click first disables
 * auto work, and guards the TLM tooltip path while a GUI is being rebuilt.
 *
 * <p>The virtual entry itself is added from the TLM Init event, after all
 * native widgets (including the schedule button) have been initialized.
 */
@Mixin(value = AbstractMaidContainerGui.class, remap = true)
public abstract class AbstractMaidContainerGuiMixin {

    @Shadow(remap = false)
    protected EntityMaid maid;

    @Shadow(remap = false)
    private ScheduleButton<?> scheduleButton;

    private static final Logger TLM$LOGGER = LoggerFactory.getLogger("tlm_sincerely/maid_gui");
    private static boolean TLM$loggedIncompleteTooltip;

    /**
     * TLM can render one frame while a screen is being rebuilt or disconnected.
     * Its native renderTooltip assumes scheduleButton is already present; skip
     * that tooltip pass during the incomplete frame instead of crashing.
     */
    @Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true)
    private void tlmSincerely$skipIncompleteTooltip(GuiGraphics graphics, int mouseX, int mouseY,
                                                    CallbackInfo ci) {
        if (scheduleButton == null) {
            if (!TLM$loggedIncompleteTooltip) {
                TLM$loggedIncompleteTooltip = true;
                TLM$LOGGER.warn("Skipping maid GUI tooltip while scheduleButton is not initialized");
            }
            ci.cancel();
        }
    }

    /**
     * Before any normal task button is processed, send a "disable auto
     * work" packet for this maid. The original click then proceeds and
     * sets the task normally; the scheduler will see auto work is off
     * and won't immediately pre-empt the user's pick.
     */
    @Inject(method = "taskButtonPressed", at = @At("HEAD"), remap = false)
    private void tlmSincerely$disableBeforeTaskPick(IMaidTask t, boolean isSelect, CallbackInfo ci) {
        if (!isSelect || this.maid == null) {
            return;
        }
        AutoWorkNetworking.channel().sendToServer(
                new SetMaidAutoWorkC2SPacket(maid.getUUID(), false));
    }

}
