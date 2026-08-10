package com.github.tartaricacid.tlm_sincerely.client.gui.autowork;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.resources.ResourceLocation;

/**
 * Dedicated auto-work top tab.
 *
 * <p>Reuses TLM's selected-tab background slice from
 * {@code maid_gui_side.png} (at the {@code backgroundU} column, e.g.
 * 182 for the fourth tab) and renders its own 16×16 auto-work emblem
 * from the mod's auto-work GUI texture
 * ({@code textures/gui/maid_gui_sincerely.png}) at
 * {@code (U=171, V=0)} offset 4,6 from the button top-left.
 */
public final class AutoWorkMaidTabButton extends
        com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.MaidTabButton {
    private static final ResourceLocation TLM_TAB_TEXTURE = new ResourceLocation(
            "touhou_little_maid", "textures/gui/maid_gui_side.png");
    private static final ResourceLocation AUTO_WORK_ICON_TEXTURE =
            new ResourceLocation("tlm_sincerely", "textures/gui/maid_gui_sincerely.png");

    private static final int TAB_WIDTH = 24;
    private static final int TAB_HEIGHT = 26;
    private static final int TAB_BACKGROUND_Y = 21;

    private static final int ICON_U = 171;
    private static final int ICON_V = 0;
    private static final int ICON_SIZE = 16;
    private static final int ICON_OFFSET_X = 4;
    private static final int ICON_OFFSET_Y = 6;

    private final int backgroundU;

    public AutoWorkMaidTabButton(int x, int y, int backgroundU, String name, Button.OnPress onPress) {
        super(x, y, backgroundU, name, onPress);
        this.backgroundU = backgroundU;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        RenderSystem.enableDepthTest();
        // TLM draws this slice only for the selected/inactive tab state.
        // Intentionally do not call super: super would also draw TLM's unused
        // legacy glyph at backgroundU, 47.
        if (!this.active) {
            graphics.blit(TLM_TAB_TEXTURE, getX(), getY(), backgroundU, TAB_BACKGROUND_Y,
                    TAB_WIDTH, TAB_HEIGHT, 256, 256);
        }
        graphics.blit(AUTO_WORK_ICON_TEXTURE,
                getX() + ICON_OFFSET_X, getY() + ICON_OFFSET_Y,
                ICON_U, ICON_V, ICON_SIZE, ICON_SIZE, 256, 256);
    }
}
