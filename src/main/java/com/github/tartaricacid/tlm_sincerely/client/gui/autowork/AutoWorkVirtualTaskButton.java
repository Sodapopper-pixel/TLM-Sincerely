package com.github.tartaricacid.tlm_sincerely.client.gui.autowork;

import com.github.tartaricacid.tlm_sincerely.client.network.ClientAutoWorkService;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkNetworking;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkSnapshot;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.SetMaidAutoWorkC2SPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Standalone "auto work switch" control below the maid portrait. It is a
 * plain {@link AbstractWidget} because TLM's {@code TaskButton} requires a
 * non-null {@code IMaidTask}; deliberately keeping it out of the task list
 * avoids breaking TLM's task paging and open/close visibility rules.
 *
 * <p>Click toggles the maid's auto work enabled flag via
 * {@link SetMaidAutoWorkC2SPacket} and never calls
 * {@code EntityMaid.setTask}.
 */
public final class AutoWorkVirtualTaskButton extends AbstractWidget {
    /** Reuse the task-list texture for visual consistency with TLM buttons. */
    private static final ResourceLocation TASK_TEXTURE =
            new ResourceLocation("touhou_little_maid", "textures/gui/maid_gui_task.png");
    /** Vanilla clock icon — safe and visible. */
    private static final ItemStack ICON = new ItemStack(Items.CLOCK);

    private final UUID maidId;

    public AutoWorkVirtualTaskButton(int x, int y, int width, int height, UUID maidId) {
        super(x, y, width, height, Component.translatable("gui.tlm_sincerely.task.auto_switch"));
        this.maidId = maidId;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        Optional<AutoWorkSnapshot.MaidEntry> entry =
                ClientAutoWorkService.get().findMaid(maidId);
        boolean current = entry.map(AutoWorkSnapshot.MaidEntry::enabled).orElse(false);
        AutoWorkNetworking.channel().sendToServer(
                new SetMaidAutoWorkC2SPacket(maidId, !current));
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        RenderSystem.enableDepthTest();
        boolean enabled = ClientAutoWorkService.get().findMaid(maidId)
                .map(AutoWorkSnapshot.MaidEntry::enabled).orElse(false);
        boolean globalOn = PriorityConfig.ENABLED.get();
        int yTex = 28;
        if (this.isHoveredOrFocused()) {
            yTex += 20;
        }
        // Same texture region TLM uses for task list entries
        // (93,28) = 83x19 normal; (93,48) = 83x19 hovered.
        graphics.blit(TASK_TEXTURE, getX(), getY(), 93, yTex, width, height, 256, 256);

        // Icon
        graphics.renderItem(ICON, getX() + 2, getY() + 1);
        // Label
        int labelColor;
        if (!globalOn) {
            labelColor = 0xFFFF5555; // opaque red disabled-hint
        } else if (enabled) {
            labelColor = 0xFF3380FF; // opaque blue active
        } else {
            labelColor = 0xFF333333; // opaque normal
        }
        Minecraft mc = Minecraft.getInstance();
        graphics.drawString(mc.font, getMessage(), getX() + 20, getY() + 5, labelColor, false);

        // Blue outline when enabled
        if (enabled && globalOn) {
            int outline = 0x803380FF;
            graphics.fill(getX(), getY(), getX() + width, getY() + 1, outline);
            graphics.fill(getX(), getY() + height - 1, getX() + width, getY() + height, outline);
            graphics.fill(getX(), getY(), getX() + 1, getY() + height, outline);
            graphics.fill(getX() + width - 1, getY(), getX() + width, getY() + height, outline);
        }

        // Tooltip (cheap, no render-pass cost when not hovered)
        if (isHoveredOrFocused()) {
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
