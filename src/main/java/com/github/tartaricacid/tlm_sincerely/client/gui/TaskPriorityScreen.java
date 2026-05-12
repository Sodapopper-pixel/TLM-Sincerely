package com.github.tartaricacid.tlm_sincerely.client.gui;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
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
    private static final ResourceLocation BG_TEXTURE = new ResourceLocation("tlm_sincerely", "textures/gui/priority_gui_bg.png");
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
        int taskStartY = guiTop + 44;
        int row1Y = guiTop + 4;
        int row2Y = guiTop + 20;

        boolean isEnabled = PriorityConfig.ENABLED.get();
        String enableText = isEnabled ? 
                Component.translatable("gui.tlm_sincerely.priority.enabled.on").getString() : 
                Component.translatable("gui.tlm_sincerely.priority.enabled.off").getString();
        String enableLabel = Component.translatable("gui.tlm_sincerely.priority.enable_label").getString();

        addRenderableWidget(Button.builder(
                        Component.literal(enableLabel + ": " + enableText),
                        b -> toggleEnabled())
                .pos(leftColX, row1Y).size(110, 14).build());

        this.presetNameField = new EditBox(font, leftColX + 112, row1Y, 50, 14, Component.literal("preset_name"));
        presetNameField.setValue(editingPreset.getName());
        presetNameField.setMaxLength(16);
        addRenderableWidget(presetNameField);

        addRenderableWidget(Button.builder(Component.literal("<"), b -> switchPreset(-1))
                .pos(leftColX + 164, row1Y).size(12, 14).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> switchPreset(1))
                .pos(leftColX + 177, row1Y).size(12, 14).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.tlm_sincerely.priority.new"),
                        b -> newPreset()).pos(rightColX, row2Y).size(36, 14).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.tlm_sincerely.priority.delete"),
                        b -> deletePreset()).pos(rightColX + 38, row2Y).size(36, 14).build());

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
                    task, true, null, -1, () -> addToPriority(task.getUid())));
        }

        int leftPageBtnY = taskStartY + MAX_VISIBLE * TASK_HEIGHT + 2;
        if (leftPage > 0) {
            addRenderableWidget(Button.builder(Component.literal("▲"),
                            b -> { leftPage--; buildWidgets(); })
                    .pos(leftColX + 48, leftPageBtnY).size(16, 12).build());
        }
        if (leftPage < maxPageLeft) {
            addRenderableWidget(Button.builder(Component.literal("▼"),
                            b -> { leftPage++; buildWidgets(); })
                    .pos(leftColX + 66, leftPageBtnY).size(16, 12).build());
        }

        int maxPageRight = Math.max(0, (sortedTasks.size() - 1) / MAX_VISIBLE);
        if (rightPage > maxPageRight) rightPage = maxPageRight;

        for (int i = 0; i < MAX_VISIBLE; i++) {
            int index = rightPage * MAX_VISIBLE + i;
            if (index >= sortedTasks.size()) break;
            IMaidTask task = sortedTasks.get(index);
            int priority = editingPreset.getPriorities().getOrDefault(task.getUid(), 0);
            addRenderableWidget(new TaskEntryButton(rightColX, taskStartY + i * TASK_HEIGHT, 118, 17,
                    task, false, this, index, () -> {
                        int current = editingPreset.getPriorities().getOrDefault(task.getUid(), 10);
                        editingPreset.setPriority(task.getUid(), current % 10 + 1);
                        TaskPriorityManager.savePresets();
                        buildWidgets();
                    }));
        }

        int rightPageBtnY = taskStartY + MAX_VISIBLE * TASK_HEIGHT + 2;
        if (rightPage > 0) {
            addRenderableWidget(Button.builder(Component.literal("◀"),
                            b -> { rightPage--; buildWidgets(); })
                    .pos(rightColX + 48, rightPageBtnY).size(16, 12).build());
        }
        if (rightPage < maxPageRight) {
            addRenderableWidget(Button.builder(Component.literal("▶"),
                            b -> { rightPage++; buildWidgets(); })
                    .pos(rightColX + 66, rightPageBtnY).size(16, 12).build());
        }
    }

    private void toggleEnabled() {
        boolean current = PriorityConfig.ENABLED.get();
        PriorityConfig.ENABLED.set(!current);
        buildWidgets();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        int rightColX = guiLeft + 133;
        int taskStartY = guiTop + 44;
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

    private void addToPriority(ResourceLocation taskId) {
        editingPreset.setPriority(taskId, 10);
        TaskPriorityManager.savePresets();
        buildWidgets();
    }

    void moveTaskUp(ResourceLocation taskId) {
        editingPreset.moveTaskUp(taskId);
        TaskPriorityManager.savePresets();
        buildWidgets();
    }

    void moveTaskDown(ResourceLocation taskId) {
        editingPreset.moveTaskDown(taskId);
        TaskPriorityManager.savePresets();
        buildWidgets();
    }

    void removeTask(ResourceLocation taskId) {
        editingPreset.removeTask(taskId);
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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderBackground(graphics);
        graphics.blit(BG_TEXTURE, guiLeft, guiTop, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
        graphics.fill(guiLeft + 125, guiTop + 28, guiLeft + 126, guiTop + IMAGE_HEIGHT - 28, 0xFF555555);

        super.render(graphics, mouseX, mouseY, partialTicks);

        graphics.drawString(font, Component.translatable("gui.tlm_sincerely.priority.unsorted"),
                guiLeft + 7, guiTop + 32, 0xAAAAAA, false);
        graphics.drawString(font, Component.translatable("gui.tlm_sincerely.priority.sorted"),
                guiLeft + 133, guiTop + 32, 0xAAAAAA, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    static class TaskEntryButton extends Button {
        private final IMaidTask task;
        private final boolean isLeftColumn;
        private final Runnable onClickAction;
        private final TaskPriorityScreen parentScreen;
        private final int sortedIndex;
        private Button moveUpBtn;
        private Button moveDownBtn;

        TaskEntryButton(int x, int y, int width, int height, IMaidTask task, boolean isLeftColumn,
                       TaskPriorityScreen parentScreen, int sortedIndex, Runnable onClickAction) {
            super(x, y, width, height, Component.empty(), b -> {}, DEFAULT_NARRATION);
            this.task = task;
            this.isLeftColumn = isLeftColumn;
            this.parentScreen = parentScreen;
            this.sortedIndex = sortedIndex;
            this.onClickAction = onClickAction;
        }

        @Override
        public void onPress() {
            onClickAction.run();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                int btnY = this.getY() + 2;
                if (sortedIndex > 0) {
                    int upBtnX = this.getX() + 84;
                    if (mouseX >= upBtnX && mouseX <= upBtnX + 14 &&
                        mouseY >= btnY && mouseY <= btnY + 12) {
                        parentScreen.moveTaskUp(task.getUid());
                        return true;
                    }
                }
                if (parentScreen != null && sortedIndex < parentScreen.editingPreset.getSortedTasks().size() - 1) {
                    int downBtnX = this.getX() + 100;
                    if (mouseX >= downBtnX && mouseX <= downBtnX + 14 &&
                        mouseY >= btnY && mouseY <= btnY + 12) {
                        parentScreen.moveTaskDown(task.getUid());
                        return true;
                    }
                }
            }
            if (button == 1 && !isLeftColumn && parentScreen != null) {
                if (mouseX >= this.getX() && mouseX <= this.getX() + this.width &&
                    mouseY >= this.getY() && mouseY <= this.getY() + this.height) {
                    parentScreen.removeTask(task.getUid());
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
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
                graphics.drawString(Minecraft.getInstance().font, "+", this.getX() + 20, this.getY() + 5, 0x22AA22, false);
                graphics.drawString(Minecraft.getInstance().font, task.getName(), this.getX() + 30, this.getY() + 5, 0x333333, false);
            } else {
                int priority = parentScreen != null && parentScreen.editingPreset != null
                        ? parentScreen.editingPreset.getPriorities().getOrDefault(task.getUid(), 0)
                        : 0;
                graphics.renderItem(task.getIcon(), this.getX() + 2, this.getY() + 1);
                String prioText = priority > 0 ? String.valueOf(priority) : "-";
                graphics.drawString(Minecraft.getInstance().font, prioText, this.getX() + 20, this.getY() + 5, 0xFF6600, false);
                graphics.drawString(Minecraft.getInstance().font, task.getName(), this.getX() + 30, this.getY() + 5, 0x333333, false);

                int btnY = this.getY() + 2;
                if (sortedIndex > 0) {
                    moveUpBtn = Button.builder(Component.literal("▲"),
                            b -> parentScreen.moveTaskUp(task.getUid()))
                            .pos(this.getX() + 84, btnY).size(14, 12).build();
                    moveUpBtn.render(graphics, mouseX, mouseY, partialTicks);
                } else {
                    moveUpBtn = null;
                }
                if (parentScreen != null && sortedIndex < parentScreen.editingPreset.getSortedTasks().size() - 1) {
                    moveDownBtn = Button.builder(Component.literal("▼"),
                            b -> parentScreen.moveTaskDown(task.getUid()))
                            .pos(this.getX() + 100, btnY).size(14, 12).build();
                    moveDownBtn.render(graphics, mouseX, mouseY, partialTicks);
                } else {
                    moveDownBtn = null;
                }
            }
        }
    }
}
