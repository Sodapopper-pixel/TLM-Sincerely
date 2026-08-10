package com.github.tartaricacid.tlm_sincerely.client.gui.autowork;

import com.github.tartaricacid.tlm_sincerely.client.network.ClientAutoWorkService;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkNetworking;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkSnapshot;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.SetMaidAutoWorkC2SPacket;
import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Standalone "auto work switch" control below the maid portrait.
 *
 * <p>The visual is a 69×29 slot drawn from
 * {@code textures/gui/maid_gui_sincerely.png}; the clickable region is
 * restricted to the inner 63×19 area (texture offset (4, 8)) so the
 * transparent padding does not capture stray clicks.
 *
 * <p>One of four texture rows is chosen by the (globalOn, enabled) pair:
 * {@code v0 = normal-off, v30 = global-off, v60 = normal-on, v90 = global-on}.
 * The label is drawn black, centered inside the 63×19 click area.
 *
 * <p>Hover does not switch texture (the four states are already exhaustive)
 * and the previous clock icon / blue outline / state-color text have been
 * removed in favor of the unified style.
 */
public final class AutoWorkVirtualTaskButton extends AbstractWidget {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation TASK_TEXTURE =
            new ResourceLocation("tlm_sincerely", "textures/gui/maid_gui_sincerely.png");
    private static final int TEXTURE_SIZE = 256;

    /** Source U is fixed; only V changes per state. */
    private static final int TEX_U = 0;
    private static final int TEX_V_NORMAL_OFF = 0;
    private static final int TEX_V_GLOBAL_OFF = 30;
    private static final int TEX_V_NORMAL_ON = 60;
    private static final int TEX_V_GLOBAL_ON = 90;

    /** Inner clickable area inside the 69×29 texture slot. */
    private static final int HIT_X = 4;
    private static final int HIT_Y = 8;
    private static final int HIT_WIDTH = 63;
    private static final int HIT_HEIGHT = 19;

    private static final int LABEL_COLOR = 0xFF000000;

    private final UUID maidId;

    public AutoWorkVirtualTaskButton(int x, int y, int width, int height, UUID maidId) {
        super(x, y, width, height, Component.translatable("gui.tlm_sincerely.task.auto_switch"));
        this.maidId = maidId;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        boolean globalOn = PriorityConfig.ENABLED.get();
        if (!globalOn) {
            setFocused(false);
            LOGGER.debug("Ignored auto-work toggle while globally disabled: maid={}, hovered={}, focused={}",
                    maidId, isHovered(), isFocused());
            return;
        }
        Optional<AutoWorkSnapshot.MaidEntry> entry =
                ClientAutoWorkService.get().findMaid(maidId);
        boolean current = entry.map(AutoWorkSnapshot.MaidEntry::enabled).orElse(false);
        AutoWorkNetworking.channel().sendToServer(
                new SetMaidAutoWorkC2SPacket(maidId, !current));
        setFocused(false);
        LOGGER.debug("Sent auto-work toggle: maid={}, beforeEnabled={}, hovered={}", maidId, current, isHovered());
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        int hitX = getX() + HIT_X;
        int hitY = getY() + HIT_Y;
        return mouseX >= hitX && mouseX < hitX + HIT_WIDTH
                && mouseY >= hitY && mouseY < hitY + HIT_HEIGHT;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        RenderSystem.enableDepthTest();
        boolean enabled = ClientAutoWorkService.get().findMaid(maidId)
                .map(AutoWorkSnapshot.MaidEntry::enabled).orElse(false);
        boolean globalOn = PriorityConfig.ENABLED.get();
        int v;
        if (!globalOn) {
            v = enabled ? TEX_V_GLOBAL_ON : TEX_V_GLOBAL_OFF;
        } else {
            v = enabled ? TEX_V_NORMAL_ON : TEX_V_NORMAL_OFF;
        }
        graphics.blit(TASK_TEXTURE, getX(), getY(), TEX_U, v, getWidth(), getHeight(), TEXTURE_SIZE, TEXTURE_SIZE);

        // Label, centered inside the inner 63×19 click area.
        Minecraft mc = Minecraft.getInstance();
        int hitX = getX() + HIT_X;
        int hitY = getY() + HIT_Y;
        int textX = hitX + (HIT_WIDTH - mc.font.width(getMessage())) / 2;
        int textY = hitY + (HIT_HEIGHT - mc.font.lineHeight) / 2;
        graphics.drawString(mc.font, getMessage(), textX, textY, LABEL_COLOR, false);

        // Tooltip only when the cursor is inside the inner hit area.
        if (mouseX >= hitX && mouseX < hitX + HIT_WIDTH
                && mouseY >= hitY && mouseY < hitY + HIT_HEIGHT) {
            renderHoverTooltip(graphics, mc, mouseX, mouseY);
        }
    }

    private void renderHoverTooltip(GuiGraphics graphics, Minecraft mc, int mouseX, int mouseY) {
        List<Component> tips = new ArrayList<>();
        tips.add(getMessage().copy().withStyle(ChatFormatting.GOLD));
        tips.add(Component.translatable("gui.tlm_sincerely.task.auto_switch.desc")
                .withStyle(ChatFormatting.GRAY));
        if (!PriorityConfig.ENABLED.get()) {
            tips.add(Component.translatable("gui.tlm_sincerely.task.auto_switch.disabled_hint")
                    .withStyle(ChatFormatting.RED));
        }
        graphics.renderComponentTooltip(mc.font, tips, mouseX, mouseY);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
