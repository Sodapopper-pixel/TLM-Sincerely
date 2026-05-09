package com.github.tartaricacid.tlm_sincerely.client.gui;

import com.github.tartaricacid.tlm_sincerely.priority.TaskPriorityManager;
import com.github.tartaricacid.tlm_sincerely.priority.TaskPriorityPreset;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

@OnlyIn(Dist.CLIENT)
public class TaskPriorityScreen extends Screen {
    private static final ResourceLocation TASK_TEXTURE = new ResourceLocation("touhou_little_maid", "textures/gui/maid_gui_task.png");
    private static final int IMAGE_WIDTH = 256;
    private static final int IMAGE_HEIGHT = 256;
    private static final int TASK_HEIGHT = 19;
    private static final int MAX_VISIBLE = 10;

    private final EntityMaid maid;
    private int guiLeft;
    private int guiTop;
    private int leftPage = 0;
    private int rightPage = 0;
    private String activePresetName;
    private TaskPriorityPreset editingPreset;
    private final List<IMaidTask> allTasks;
    private EditBox presetNameField;

    public TaskPriorityScreen(EntityMaid maid) {
        super(Component.translatable("gui.tlm_sincerely.priority.title"));
        this.maid = maid;
        TaskPriorityManager.loadPresets();
        this.activePresetName = TaskPriorityManager.getActivePresetName();
        this.editingPreset = TaskPriorityManager.getActivePreset();
        this.allTasks = TaskManager.getTaskIndex();
    }

    @Override
    protected void init() {
        super.init();
        this.guiLeft = (this.width - IMAGE_WIDTH) / 2;
        this.guiTop = (this.height - IMAGE_HEIGHT) / 2;
        buildWidgets();
    }

    private void buildWidgets() {
        clearWidgets();

        int leftColX = guiLeft + 7;
        int rightColX = guiLeft + 133;
        int taskStartY = guiTop + 32;
        int presetBarY = guiTop + 4;
        int bottomBarY = guiTop + IMAGE_HEIGHT - 24;

        this.presetNameField = new EditBox(font, leftColX + 32, presetBarY, 56, 14, Component.literal("preset_name"));
        presetNameField.setValue(editingPreset.getName());
        presetNameField.setMaxLength(16);
        addRenderableWidget(presetNameField);

        addRenderableWidget(Button.builder(Component.translatable("gui.tlm_sincerely.priority.save"),
                        b -> savePreset()).pos(leftColX + 90, presetBarY).size(36, 14).build());

        addRenderableWidget(Button.builder(Component.literal("<"), b -> switchPreset(-1))
                .pos(leftColX, presetBarY).size(14, 14).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> switchPreset(1))
                .pos(leftColX + 16, presetBarY).size(14, 14).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.tlm_sincerely.priority.new"),
                        b -> newPreset()).pos(rightColX, presetBarY).size(30, 14).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.tlm_sincerely.priority.delete"),
                        b -> deletePreset()).pos(rightColX + 32, presetBarY).size(30, 14).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.tlm_sincerely.priority.apply"),
                        b -> applyPreset()).pos(rightColX, bottomBarY).size(55, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),
                        b -> onClose()).pos(rightColX + 60, bottomBarY).size(55, 20).build());

        List<IMaidTask> unsortedTasks = getUnsortedTasks();
        List<IMaidTask> sortedTasks = editingPreset.getSortedTasks().stream()
                .map(id -> TaskManager.findTask(id).orElse(null))
                .filter(t -> t != null)
                .toList();

        int maxPageLeft = Math.max(0, (unsortedTasks.size() - 1) / MAX_VISIBLE);
        if (leftPage > maxPageLeft) leftPage = maxPageLeft;

        for (int i = 0; i < MAX_VISIBLE; i++) {
            int index = leftPage * MAX_VISIBLE + i;
            if (index >= unsortedTasks.size()) break;
            IMaidTask task = unsortedTasks.get(index);
            addRenderableWidget(new TaskEntryButton(leftColX, taskStartY + i * TASK_HEIGHT, 118, 17,
                    task, true, () -> addToPriority(task.getUid())));
        }

        if (leftPage > 0) {
            addRenderableWidget(Button.builder(Component.literal("▲"),
                            b -> { leftPage--; buildWidgets(); })
                    .pos(leftColX + 48, taskStartY + MAX_VISIBLE * TASK_HEIGHT + 2).size(16, 12).build());
        }
        if (leftPage < maxPageLeft) {
            addRenderableWidget(Button.builder(Component.literal("▼"),
                            b -> { leftPage++; buildWidgets(); })
                    .pos(leftColX + 64, taskStartY + MAX_VISIBLE * TASK_HEIGHT + 2).size(16, 12).build());
        }

        int maxPageRight = Math.max(0, (sortedTasks.size() - 1) / MAX_VISIBLE);
        if (rightPage > maxPageRight) rightPage = maxPageRight;

        for (int i = 0; i < MAX_VISIBLE; i++) {
            int index = rightPage * MAX_VISIBLE + i;
            if (index >= sortedTasks.size()) break;
            IMaidTask task = sortedTasks.get(index);
            int priority = editingPreset.getPriorities().getOrDefault(task.getUid(), 0);
            addRenderableWidget(new TaskEntryButton(rightColX, taskStartY + i * TASK_HEIGHT, 118, 17,
                    task, false, () -> {
                        int current = editingPreset.getPriorities().getOrDefault(task.getUid(), 10);
                        editingPreset.setPriority(task.getUid(), current % 10 + 1);
                        TaskPriorityManager.savePresets();
                        buildWidgets();
                    }));
        }

        if (rightPage > 0) {
            addRenderableWidget(Button.builder(Component.literal("▲"),
                            b -> { rightPage--; buildWidgets(); })
                    .pos(rightColX + 48, taskStartY + MAX_VISIBLE * TASK_HEIGHT + 2).size(16, 12).build());
        }
        if (rightPage < maxPageRight) {
            addRenderableWidget(Button.builder(Component.literal("▼"),
                            b -> { rightPage++; buildWidgets(); })
                    .pos(rightColX + 64, taskStartY + MAX_VISIBLE * TASK_HEIGHT + 2).size(16, 12).build());
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        int rightColX = guiLeft + 133;
        int taskStartY = guiTop + 32;
        int rightColW = 118;

        if (mouseX >= rightColX && mouseX <= rightColX + rightColW &&
                mouseY >= taskStartY && mouseY <= taskStartY + MAX_VISIBLE * TASK_HEIGHT) {
            int row = (int) ((mouseY - taskStartY) / TASK_HEIGHT);
            List<IMaidTask> sortedTasks = editingPreset.getSortedTasks().stream()
                    .map(id -> TaskManager.findTask(id).orElse(null))
                    .filter(t -> t != null)
                    .toList();
            int index = rightPage * MAX_VISIBLE + row;
            if (index >= 0 && index < sortedTasks.size()) {
                IMaidTask task = sortedTasks.get(index);
                int current = editingPreset.getPriorities().getOrDefault(task.getUid(), 10);
                int delta = scrollY > 0 ? 1 : -1;
                int next = ((current - 1 + delta + 10) % 10) + 1;
                editingPreset.setPriority(task.getUid(), next);
                TaskPriorityManager.savePresets();
                buildWidgets();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollY);
    }

    private List<IMaidTask> getUnsortedTasks() {
        List<IMaidTask> unsorted = new ArrayList<>();
        for (IMaidTask task : allTasks) {
            if (!editingPreset.hasTask(task.getUid())) {
                unsorted.add(task);
            }
        }
        return unsorted;
    }

    private void addToPriority(net.minecraft.resources.ResourceLocation taskId) {
        editingPreset.setPriority(taskId, 10);
        TaskPriorityManager.savePresets();
        buildWidgets();
    }

    private void savePreset() {
        String newName = presetNameField.getValue().trim();
        if (newName.isEmpty()) return;
        if (!newName.equals(editingPreset.getName())) {
            TaskPriorityManager.renamePreset(editingPreset.getName(), newName);
            activePresetName = newName;
        }
        TaskPriorityManager.savePresets();
        buildWidgets();
    }

    private void newPreset() {
        String name = "预设 " + (TaskPriorityManager.getPresetNames().size() + 1);
        TaskPriorityPreset preset = new TaskPriorityPreset(name);
        TaskPriorityManager.addPreset(preset);
        activePresetName = name;
        editingPreset = preset;
        TaskPriorityManager.setActivePreset(name);
        buildWidgets();
    }

    private void deletePreset() {
        TaskPriorityManager.removePreset(activePresetName);
        activePresetName = TaskPriorityManager.getActivePresetName();
        editingPreset = TaskPriorityManager.getActivePreset();
        if (editingPreset == null) {
            editingPreset = new TaskPriorityPreset("默认预设");
            TaskPriorityManager.addPreset(editingPreset);
            activePresetName = editingPreset.getName();
        }
        TaskPriorityManager.setActivePreset(activePresetName);
        buildWidgets();
    }

    private void switchPreset(int direction) {
        List<String> names = TaskPriorityManager.getPresetNames();
        int idx = names.indexOf(activePresetName);
        if (idx < 0) return;
        int newIdx = Math.floorMod(idx + direction, names.size());
        activePresetName = names.get(newIdx);
        editingPreset = TaskPriorityManager.getPreset(activePresetName);
        TaskPriorityManager.setActivePreset(activePresetName);
        buildWidgets();
    }

    private void applyPreset() {
        TaskPriorityManager.setActivePreset(activePresetName);
        TaskPriorityManager.savePresets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderBackground(graphics);
        graphics.fill(guiLeft, guiTop, guiLeft + IMAGE_WIDTH, guiTop + IMAGE_HEIGHT, 0xC0101010);
        graphics.fill(guiLeft + 125, guiTop, guiLeft + 126, guiTop + IMAGE_HEIGHT, 0xFF555555);

        super.render(graphics, mouseX, mouseY, partialTicks);

        String presetLabel = "[" + activePresetName + "]";
        graphics.drawString(font, presetLabel, guiLeft + 136, guiTop + 8, 0xFFFFFF, false);

        graphics.drawString(font, Component.translatable("gui.tlm_sincerely.priority.unsorted"),
                guiLeft + 7, guiTop + 20, 0xAAAAAA, false);
        graphics.drawString(font, Component.translatable("gui.tlm_sincerely.priority.sorted"),
                guiLeft + 133, guiTop + 20, 0xAAAAAA, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    static class TaskEntryButton extends Button {
        private final IMaidTask task;
        private final boolean isLeftColumn;
        private final Runnable onClickAction;

        TaskEntryButton(int x, int y, int width, int height, IMaidTask task, boolean isLeftColumn, Runnable onClickAction) {
            super(x, y, width, height, Component.empty(), b -> {}, DEFAULT_NARRATION);
            this.task = task;
            this.isLeftColumn = isLeftColumn;
            this.onClickAction = onClickAction;
        }

        @Override
        public void onPress() {
            onClickAction.run();
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            RenderSystem.enableDepthTest();
            int yTex = 28;
            if (this.isHoveredOrFocused()) {
                yTex += 20;
            }
            graphics.blit(TASK_TEXTURE, this.getX(), this.getY(), 93, yTex, this.width, this.height, 256, 256);

            if (isLeftColumn) {
                graphics.renderItem(task.getIcon(), this.getX() + 2, this.getY() + 1);
                graphics.drawString(Minecraft.getInstance().font, "+ ", this.getX() + 22, this.getY() + 5, 0x22AA22, false);
                graphics.drawString(Minecraft.getInstance().font, task.getName(), this.getX() + 34, this.getY() + 5, 0x333333, false);
            } else {
                int priority = TaskPriorityManager.getActivePreset() != null
                        ? TaskPriorityManager.getActivePreset().getPriorities().getOrDefault(task.getUid(), 0)
                        : 0;
                graphics.renderItem(task.getIcon(), this.getX() + 2, this.getY() + 1);
                String prioText = priority > 0 ? String.valueOf(priority) : "-";
                graphics.drawString(Minecraft.getInstance().font, prioText, this.getX() + 22, this.getY() + 5, 0xFF6600, false);
                graphics.drawString(Minecraft.getInstance().font, task.getName(), this.getX() + 34, this.getY() + 5, 0x333333, false);
            }
        }
    }
}
