package com.github.tartaricacid.tlm_sincerely.client.gui.priority;

import com.github.tartaricacid.tlm_sincerely.priority.TaskPriorityManager;
import com.github.tartaricacid.tlm_sincerely.priority.TaskPriorityPreset;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

public class PriorityContainerGui extends AbstractContainerScreen<PriorityContainer> {
    private static final int TASK_COUNT_PER_PAGE = 11;
    private static final int BUTTON_HEIGHT = 18;

    private int leftPage = 0;
    private int rightPage = 0;
    private String activePresetName;
    private TaskPriorityPreset editingPreset;
    private final List<IMaidTask> allTasks;

    private EditBox presetNameField;

    public PriorityContainerGui(PriorityContainer container, Inventory inventory, Component title) {
        super(container, inventory, title);
        this.imageWidth = 256;
        this.imageHeight = 256;
        TaskPriorityManager.loadPresets();
        this.activePresetName = TaskPriorityManager.getActivePresetName();
        this.editingPreset = TaskPriorityManager.getActivePreset();
        this.allTasks = TaskManager.getTaskIndex();
    }

    @Override
    protected void init() {
        super.init();
        buildWidgets();
    }

    protected void buildWidgets() {
        clearWidgets();

        int presetBarY = topPos + 4;
        int leftColX = leftPos + 7;
        int rightColX = leftPos + 133;
        int taskStartY = topPos + 28;
        int bottomBarY = topPos + imageHeight - 24;

        this.presetNameField = new EditBox(font, rightColX + 50, presetBarY, 70, 14,
                Component.literal("preset_name"));
        presetNameField.setValue(editingPreset.getName());
        presetNameField.setMaxLength(32);
        addRenderableWidget(presetNameField);

        addRenderableWidget(Button.builder(Component.translatable("gui.tlm_sincerely.priority.save"),
                        b -> savePreset())
                .pos(rightColX + 50, presetBarY + 16).size(34, 14).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.tlm_sincerely.priority.new"),
                        b -> newPreset())
                .pos(leftColX, presetBarY).size(34, 14).build());

        addRenderableWidget(Button.builder(Component.literal("<"),
                        b -> switchPreset(-1))
                .pos(leftColX + 36, presetBarY).size(14, 14).build());

        addRenderableWidget(Button.builder(Component.literal(">"),
                        b -> switchPreset(1))
                .pos(leftColX + 52, presetBarY).size(14, 14).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.tlm_sincerely.priority.delete"),
                        b -> deletePreset())
                .pos(leftColX + 68, presetBarY).size(34, 14).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.tlm_sincerely.priority.apply"),
                        b -> applyPreset())
                .pos(rightColX, bottomBarY).size(60, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),
                        b -> onClose())
                .pos(rightColX + 65, bottomBarY).size(60, 20).build());

        List<IMaidTask> unsortedTasks = getUnsortedTasks();
        List<IMaidTask> sortedTasks = editingPreset.getSortedTasks().stream()
                .map(id -> TaskManager.findTask(id).orElse(null))
                .filter(t -> t != null)
                .toList();

        int maxPageLeft = Math.max(0, (unsortedTasks.size() - 1) / TASK_COUNT_PER_PAGE);
        if (leftPage > maxPageLeft) leftPage = maxPageLeft;

        for (int i = 0; i < TASK_COUNT_PER_PAGE; i++) {
            int index = leftPage * TASK_COUNT_PER_PAGE + i;
            if (index >= unsortedTasks.size()) break;
            IMaidTask task = unsortedTasks.get(index);
            addRenderableWidget(Button.builder(
                            Component.literal("+ ").append(task.getName()),
                            b -> addToPriority(task.getUid()))
                    .pos(leftColX, taskStartY + i * BUTTON_HEIGHT).size(118, 16).build());
        }

        int maxPageRight = Math.max(0, (sortedTasks.size() - 1) / TASK_COUNT_PER_PAGE);
        if (rightPage > maxPageRight) rightPage = maxPageRight;

        for (int i = 0; i < TASK_COUNT_PER_PAGE; i++) {
            int index = rightPage * TASK_COUNT_PER_PAGE + i;
            if (index >= sortedTasks.size()) break;
            IMaidTask task = sortedTasks.get(index);
            int priority = editingPreset.getPriorities().getOrDefault(task.getUid(), 0);

            addRenderableWidget(Button.builder(
                            Component.literal(String.valueOf(priority)).append(" "),
                            b -> cyclePriority(task.getUid()))
                    .pos(rightColX, taskStartY + i * BUTTON_HEIGHT).size(16, 16).build());

            addRenderableWidget(Button.builder(
                            Component.literal("x ").append(task.getName()),
                            b -> removeFromPriority(task.getUid()))
                    .pos(rightColX + 18, taskStartY + i * BUTTON_HEIGHT).size(100, 16).build());
        }

        if (leftPage > 0) {
            addRenderableWidget(Button.builder(Component.literal("^"),
                            b -> { leftPage--; buildWidgets(); })
                    .pos(leftColX + 48, taskStartY + TASK_COUNT_PER_PAGE * BUTTON_HEIGHT + 2).size(14, 14).build());
        }
        if (leftPage < maxPageLeft) {
            addRenderableWidget(Button.builder(Component.literal("v"),
                            b -> { leftPage++; buildWidgets(); })
                    .pos(leftColX + 64, taskStartY + TASK_COUNT_PER_PAGE * BUTTON_HEIGHT + 2).size(14, 14).build());
        }

        if (rightPage > 0) {
            addRenderableWidget(Button.builder(Component.literal("^"),
                            b -> { rightPage--; buildWidgets(); })
                    .pos(rightColX + 48, taskStartY + TASK_COUNT_PER_PAGE * BUTTON_HEIGHT + 2).size(14, 14).build());
        }
        if (rightPage < maxPageRight) {
            addRenderableWidget(Button.builder(Component.literal("v"),
                            b -> { rightPage++; buildWidgets(); })
                    .pos(rightColX + 64, taskStartY + TASK_COUNT_PER_PAGE * BUTTON_HEIGHT + 2).size(14, 14).build());
        }
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

    private void addToPriority(ResourceLocation taskId) {
        editingPreset.setPriority(taskId, 10);
        TaskPriorityManager.savePresets();
        buildWidgets();
    }

    private void removeFromPriority(ResourceLocation taskId) {
        editingPreset.removeTask(taskId);
        TaskPriorityManager.savePresets();
        buildWidgets();
    }

    private void cyclePriority(ResourceLocation taskId) {
        int current = editingPreset.getPriorities().getOrDefault(taskId, 10);
        int next = (current % 10) + 1;
        editingPreset.setPriority(taskId, next);
        TaskPriorityManager.savePresets();
        buildWidgets();
    }

    private void savePreset() {
        String newName = presetNameField.getValue().trim();
        if (newName.isEmpty()) {
            return;
        }
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
        presetNameField.setValue(name);
        TaskPriorityManager.setActivePreset(name);
        buildWidgets();
    }

    private void deletePreset() {
        TaskPriorityManager.removePreset(activePresetName);
        activePresetName = TaskPriorityManager.getActivePresetName();
        editingPreset = TaskPriorityManager.getActivePreset();
        presetNameField.setValue(activePresetName);
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
        presetNameField.setValue(activePresetName);
        buildWidgets();
    }

    private void applyPreset() {
        TaskPriorityManager.setActivePreset(activePresetName);
        TaskPriorityManager.savePresets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTicks);

        String presetLabel = "[" + activePresetName + "]";
        graphics.drawString(font, presetLabel, leftPos + 104, topPos + 6, 0xFFFFFF, false);

        graphics.drawString(font, Component.translatable("gui.tlm_sincerely.priority.unsorted"),
                leftPos + 7, topPos + 20, 0xAAAAAA, false);
        graphics.drawString(font, Component.translatable("gui.tlm_sincerely.priority.sorted"),
                leftPos + 133, topPos + 20, 0xAAAAAA, false);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
        renderBackground(graphics);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xC0101010);
        graphics.fill(leftPos + 125, topPos, leftPos + 126, topPos + imageHeight, 0xFF555555);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    }
}
