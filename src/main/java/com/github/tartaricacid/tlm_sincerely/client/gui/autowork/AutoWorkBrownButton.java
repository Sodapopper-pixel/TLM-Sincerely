package com.github.tartaricacid.tlm_sincerely.client.gui.autowork;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * TLM-styled button used by the auto work config screen.
 *
 * <p>Two textures are supported:
 * <ul>
 *   <li>{@link #TEXTURE_GUI} — the mod's own auto-work GUI texture
 *       (see {@code wiki-reference/gui-textures/纹理标注.md}).</li>
 *   <li>{@link #TEXTURE_TASK} — reused TLM task-list background for the
 *       delete / move buttons so they match the rest of the maid UI.</li>
 * </ul>
 *
 * <p>The source region is taken 1:1 from the texture at
 * {@code (texU, texV)} with width/height matching the widget bounds.
 * On hover (or keyboard focus) the V offset advances by
 * {@code texHoverOffset}; pass {@code 0} to disable the hover variant.
 *
 * <p>Two label modes are supported:
 * <ul>
 *   <li>{@code centered = true} — for pattern buttons (prev/next/new/...)
 *       that have no text label.</li>
 *   <li>{@code centered = false} — for task rows: a 16×16 icon anchored
 *       at the row origin and a label starting 18 pixels in. A row that
 *       is hovered/focused or click-selected flips to the texture's
 *       hover/selected variant (e.g. {@code gui(u97, v17)}).</li>
 * </ul>
 */
public class AutoWorkBrownButton extends AbstractWidget {
    /** Mod-owned auto-work GUI texture. */
    public static final ResourceLocation TEXTURE_GUI =
            ResourceLocation.fromNamespaceAndPath("tlm_sincerely", "textures/gui/maid_gui_sincerely.png");
    /** TLM brown task-list background, reused for the control buttons. */
    public static final ResourceLocation TEXTURE_TASK =
            ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "textures/gui/maid_gui_task.png");

    private static final int TEXTURE_SIZE = 256;
    /** Uniform opaque black label color per the project's unified text style. */
    private static final int LABEL_COLOR = 0xFF000000;

    private final ResourceLocation texture;
    private final int texU;
    private final int texV;
    private final int texHoverOffset;
    private final OnPress onPress;
    private final OnPress onSecondaryPress;
    private final Component label;
    private final boolean centered;
    private final ItemStack icon;
    private final boolean selected;

    public AutoWorkBrownButton(int x, int y, int width, int height,
                               ResourceLocation texture, int texU, int texV, int texHoverOffset,
                               Component label, OnPress onPress) {
        this(x, y, width, height, texture, texU, texV, texHoverOffset, label, onPress, true,
                ItemStack.EMPTY, false, null);
    }

    /** Creates a task row with its TLM task icon and optional right-click action. */
    public AutoWorkBrownButton(int x, int y, int width, int height,
                               ResourceLocation texture, int texU, int texV, int texHoverOffset,
                               Component label, OnPress onPress, boolean centered,
                               ItemStack icon, boolean selected, OnPress onSecondaryPress) {
        super(x, y, width, height, label);
        this.texture = texture;
        this.texU = texU;
        this.texV = texV;
        this.texHoverOffset = texHoverOffset;
        this.label = label;
        this.onPress = onPress;
        this.centered = centered;
        this.icon = icon == null ? ItemStack.EMPTY : icon;
        this.selected = selected;
        this.onSecondaryPress = onSecondaryPress;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (this.active && this.visible && onPress != null) {
            onPress.onPress(this);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && this.active && this.visible
                && this.isMouseOver(mouseX, mouseY) && onSecondaryPress != null) {
            onSecondaryPress.onPress(this);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // "Selected" covers both hover/focus and the click-selected row
        // (per the texture annotation's unified mental model); both flip
        // to the hover texture variant.
        boolean selectedVariant = this.isHoveredOrFocused() || this.selected;
        int v = texV + (selectedVariant && texHoverOffset > 0 ? texHoverOffset : 0);
        RenderSystem.enableDepthTest();
        // 1:1 source region = widget bounds; the button pattern is drawn
        // entirely inside the texture slot, so resizing is fine.
        graphics.blit(texture, getX(), getY(), texU, v, getWidth(), getHeight(), TEXTURE_SIZE, TEXTURE_SIZE);

        Minecraft mc = Minecraft.getInstance();
        if (centered) {
            int textWidth = mc.font.width(label);
            if (textWidth > 0) {
                int textX = getX() + (getWidth() - textWidth) / 2;
                int textY = getY() + (getHeight() - mc.font.lineHeight) / 2;
                graphics.drawString(mc.font, label, textX, textY, LABEL_COLOR, false);
            }
        } else {
            // Task row: 16x16 icon anchored at (getX(), getY()); label at x = getX() + 18.
            if (!icon.isEmpty()) {
                graphics.renderItem(icon, getX(), getY() + (getHeight() - 16) / 2);
            }
            int textX = getX() + 18;
            int textY = getY() + (getHeight() - mc.font.lineHeight) / 2;
            String text = mc.font.plainSubstrByWidth(label.getString(),
                    Math.max(0, getX() + getWidth() - 4 - textX));
            graphics.drawString(mc.font, text, textX, textY, LABEL_COLOR, false);
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
