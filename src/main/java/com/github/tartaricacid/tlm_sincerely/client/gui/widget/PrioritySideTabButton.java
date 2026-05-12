package com.github.tartaricacid.tlm_sincerely.client.gui.widget;

import com.github.tartaricacid.touhoulittlemaid.api.client.gui.ITooltipButton;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.MaidSideTabButton;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public class PrioritySideTabButton extends MaidSideTabButton {
    private static final ResourceLocation PRIORITY_TEXTURE = new ResourceLocation("tlm_sincerely", "textures/gui/priority_side_tab.png");
    private final List<Component> tooltips;

    public PrioritySideTabButton(int x, int y, int top, OnPress onPressIn, List<Component> tooltips) {
        super(x, y, top, onPressIn, tooltips);
        this.tooltips = tooltips;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        RenderSystem.enableDepthTest();
        int top = 107 + 50;
        if (!this.active) {
            graphics.blit(PRIORITY_TEXTURE, this.getX() + 2, this.getY(), 209, top, this.width, this.height, 256, 256);
        }
        graphics.blit(PRIORITY_TEXTURE, this.getX() + 6, this.getY() + 4, 193, top + 4, 16, 16, 256, 256);
    }

    @Override
    public boolean isTooltipHovered() {
        return this.isHovered();
    }

    @Override
    public void renderTooltip(GuiGraphics graphics, Minecraft mc, int mouseX, int mouseY) {
        graphics.renderComponentTooltip(mc.font, this.tooltips, mouseX, mouseY);
    }
}
