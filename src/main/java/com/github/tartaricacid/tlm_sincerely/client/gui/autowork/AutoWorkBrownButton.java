package com.github.tartaricacid.tlm_sincerely.client.gui.autowork;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * TLM-styled brown button used by the auto work config screen.
 *
 * <p>Renders the brown button region from
 * {@code touhou_little_maid:textures/gui/maid_gui_task.png} (the same
 * texture as {@link AutoWorkVirtualTaskButton} and TLM's task list) and
 * draws an opaque black label on top. It deliberately replaces the
 * vanilla gray {@link net.minecraft.client.gui.components.Button Button}
 * so the auto work editor visually matches the rest of the maid task
 * config UI.
 *
 * <p>The widget is a plain {@link AbstractWidget}; clicks are routed
 * through {@link #onPress} which is supplied by the caller. A width of
 * {@code 83} / height of {@code 19} lines up perfectly with the source
 * texture region (u=93, v=28, v+20 on hover), but other sizes are
 * supported by scaling the same region.
 */
public class AutoWorkBrownButton extends AbstractWidget {
    /**
     * TLM brown task list background. Reused here so the auto work
     * editor visually matches the rest of the maid task config UI.
     */
    public static final ResourceLocation TEXTURE =
            new ResourceLocation("touhou_little_maid", "textures/gui/maid_gui_task.png");

    /** u / v of the normal brown button region in {@link #TEXTURE}. */
    private static final int TEX_U = 93;
    private static final int TEX_V = 28;
    private static final int TEX_HOVER_OFFSET = 20;
    private static final int TEX_WIDTH = 83;
    private static final int TEX_HEIGHT = 19;
    private static final int TEXTURE_SIZE = 256;

    /** Opaque black text color required by the TLM visual style. */
    private static final int LABEL_COLOR = 0xFF000000;
    private static final int LABEL_COLOR_DISABLED = 0xFF606060;

    private final OnPress onPress;
    private final Component label;
    private final boolean centered;

    public AutoWorkBrownButton(int x, int y, int width, int height,
                               Component label, OnPress onPress) {
        this(x, y, width, height, label, onPress, true);
    }

    public AutoWorkBrownButton(int x, int y, int width, int height,
                               Component label, OnPress onPress, boolean centered) {
        super(x, y, width, height, label);
        this.label = label;
        this.onPress = onPress;
        this.centered = centered;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (this.active && this.visible && onPress != null) {
            onPress.onPress(this);
        }
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // Match the TLM hover rule: hovered OR focused flips the
        // texture region to the highlighted variant.
        int v = TEX_V + (this.isHoveredOrFocused() ? TEX_HOVER_OFFSET : 0);
        RenderSystem.enableDepthTest();
        // blitTexture(...) rescales the source region into the widget
        // bounds, so non-standard widths/heights still render correctly
        // without stretching the original 83x19 art beyond recognition.
        graphics.blit(TEXTURE, getX(), getY(), getWidth(), getHeight(),
                TEX_U, v, TEX_WIDTH, TEX_HEIGHT, TEXTURE_SIZE, TEXTURE_SIZE);

        Minecraft mc = Minecraft.getInstance();
        int textColor = this.active ? LABEL_COLOR : LABEL_COLOR_DISABLED;
        if (centered) {
            int textWidth = mc.font.width(label);
            int textX = getX() + (getWidth() - textWidth) / 2;
            int textY = getY() + (getHeight() - mc.font.lineHeight) / 2;
            graphics.drawString(mc.font, label, textX, textY, textColor, false);
        } else {
            int textY = getY() + (getHeight() - mc.font.lineHeight) / 2;
            graphics.drawString(mc.font, label, getX() + 4, textY, textColor, false);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    /** Mirrors {@link net.minecraft.client.gui.components.Button.OnPress}. */
    @FunctionalInterface
    public interface OnPress {
        void onPress(AutoWorkBrownButton button);
    }
}
