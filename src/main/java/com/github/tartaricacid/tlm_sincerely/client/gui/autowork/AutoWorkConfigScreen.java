package com.github.tartaricacid.tlm_sincerely.client.gui.autowork;

import com.github.tartaricacid.tlm_sincerely.client.autowork.AutoWorkClientLibrary;
import com.github.tartaricacid.tlm_sincerely.client.network.ClientAutoWorkService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPreset;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.menu.AutoWorkConfigContainer;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkNetworking;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkSnapshot;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.SetMaidAutoWorkPresetC2SPacket;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Independent auto work configuration screen.
 *
 * <p>Preset editing writes the local {@link AutoWorkClientLibrary}. While this
 * maid's config page is open, adding/removing/reordering tasks also re-bakes
 * the maid's bound snapshot so the running order stays in sync.
 */
public class AutoWorkConfigScreen extends AbstractMaidContainerGui<AutoWorkConfigContainer> {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** TLM DefaultMaidTaskConfigGui right-side rectangle. Hard-coded per project policy. */
    private static final int PANEL_OFFSET_X = 80;
    private static final int PANEL_OFFSET_Y = 28;
    private static final int PANEL_WIDTH = 176;
    private static final int PANEL_HEIGHT = 137;
    private static final ResourceLocation PANEL_TEXTURE =
            new ResourceLocation("touhou_little_maid", "textures/gui/maid_gui_main.png");

    /** Row / column layout (panel-relative). */
    private static final int ROW_HEIGHT = 16;
    private static final int VISIBLE_ROWS = 5;
    private static final int ROWS_TOP = 38;
    private static final int COLUMN_WIDTH = 73;
    private static final int LEFT_COLUMN_X = 5;
    private static final int RIGHT_COLUMN_X = 87;
    private static final int SCROLLBAR_LEFT_X = 80;
    private static final int SCROLLBAR_RIGHT_X = 164;
    private static final int SCROLLBAR_WIDTH = 5;
    private static final int SCROLLBAR_MIN_THUMB_HEIGHT = 10;
    private static final int SCROLLBAR_TRACK_COLOR = 0xFF8B8B8B;
    private static final int SCROLLBAR_THUMB_COLOR = 0xFF6C4A2D;

    /** Preset row (panel-relative). */
    private static final int PRESET_BUTTON_Y = 7;
    private static final int PRESET_EDIT_Y = 6;

    /** Titles and hint (panel-relative). */
    private static final int TITLES_Y = 23;
    private static final int PRESET_COUNT_X = 118;
    private static final int PRESET_COUNT_Y = 9;
    private static final int HINT_X = 77;
    private static final int HINT_Y = 122;

    /** Bottom move / remove controls (panel-relative). */
    private static final int CONTROLS_TOP = 120;
    private static final int CONTROLS_BUTTON_SIZE = 13;

    /** Unified text color. */
    private static final int TEXT_COLOR = 0xFF000000;

    /** The widgets this screen owns, tracked so we can remove only our own on rebuild. */
    private final List<AbstractWidget> ownedWidgets = new ArrayList<>();

    /** Listener for {@link ClientAutoWorkService} snapshot updates. */
    private final Consumer<AutoWorkSnapshot> snapshotListener = snapshot -> dirty = true;
    /** Listener for local library edits (e.g. a pushed preset applied while open). */
    private final Runnable libraryListener = () -> dirty = true;

    // Cached state that depends on the current snapshot / library.
    private AutoWorkSnapshot observedSnapshot;
    private UUID activePresetId;
    private boolean dirty = true;
    private boolean snapshotListenerRegistered;
    private boolean libraryListenerRegistered;

    // Local UI state.
    private int leftScroll;
    private int rightScroll;
    /** Task ID of the currently selected ordered entry, or {@code null}. */
    private ResourceLocation selectedTaskId;
    private EditBox renameBox;
    private int availableTaskCount;
    private int orderedTaskCount;
    private ScrollColumn draggingScrollColumn = ScrollColumn.NONE;
    private boolean diagnosticsLogged;
    /** Last name written to the local library for the active preset. Guards the
     *  responder against re-submitting unchanged values and against the
     *  initial {@code setValue} during rebuild. */
    private String lastSubmittedRename = "";
    private int renameDebounceFrames = 0;
    private String pendingRename = "";
    private static final int RENAME_DEBOUNCE_FRAMES = 40;

    public AutoWorkConfigScreen(AutoWorkConfigContainer menu, net.minecraft.world.entity.player.Inventory inv,
                                Component title) {
        super(menu, inv, title);
    }

    @Override
    protected void init() {
        super.init();
        AutoWorkClientLibrary.get().ensureLoaded();
        ClientAutoWorkService.get().requestRefresh();
        observedSnapshot = ClientAutoWorkService.get().snapshotOrNull();
        activePresetId = resolveInitialPreset();
        if (!snapshotListenerRegistered) {
            ClientAutoWorkService.get().addListener(snapshotListener);
            snapshotListenerRegistered = true;
        }
        if (!libraryListenerRegistered) {
            AutoWorkClientLibrary.get().addListener(libraryListener);
            libraryListenerRegistered = true;
        }
        rebuildAutoWorkWidgets();
    }

    @Override
    public void removed() {
        if (snapshotListenerRegistered) {
            ClientAutoWorkService.get().removeListener(snapshotListener);
            snapshotListenerRegistered = false;
        }
        if (libraryListenerRegistered) {
            AutoWorkClientLibrary.get().removeListener(libraryListener);
            libraryListenerRegistered = false;
        }
        if (renameDebounceFrames > 0) {
            submitPendingRename();
        }
        super.removed();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        int panelX = getGuiLeft() + PANEL_OFFSET_X;
        int panelY = getGuiTop() + PANEL_OFFSET_Y;
        int rowsY = panelY + ROWS_TOP;
        int rowsH = rowsHeight();
        int leftX = panelX + LEFT_COLUMN_X;
        int rightX = panelX + RIGHT_COLUMN_X;
        int delta = scrollDelta > 0 ? -1 : 1;
        int columnInteractionWidth = SCROLLBAR_LEFT_X + SCROLLBAR_WIDTH - LEFT_COLUMN_X;
        if (isInside(mouseX, mouseY, leftX, rowsY, columnInteractionWidth, rowsH)) {
            leftScroll = clampScroll(leftScroll + delta, availableTaskCount);
            dirty = true;
            return true;
        }
        if (isInside(mouseX, mouseY, rightX, rowsY, columnInteractionWidth, rowsH)) {
            rightScroll = clampScroll(rightScroll + delta, orderedTaskCount);
            dirty = true;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollDelta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int panelX = getGuiLeft() + PANEL_OFFSET_X;
            int panelY = getGuiTop() + PANEL_OFFSET_Y;
            int rowsY = panelY + ROWS_TOP;
            int rowsH = rowsHeight();
            int leftTrackX = panelX + SCROLLBAR_LEFT_X;
            int rightTrackX = panelX + SCROLLBAR_RIGHT_X;
            if (isInside(mouseX, mouseY, leftTrackX, rowsY, SCROLLBAR_WIDTH, rowsH)
                    && maxScroll(availableTaskCount) > 0) {
                draggingScrollColumn = ScrollColumn.LEFT;
                updateDraggedScroll(mouseY);
                return true;
            }
            if (isInside(mouseX, mouseY, rightTrackX, rowsY, SCROLLBAR_WIDTH, rowsH)
                    && maxScroll(orderedTaskCount) > 0) {
                draggingScrollColumn = ScrollColumn.RIGHT;
                updateDraggedScroll(mouseY);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && draggingScrollColumn != ScrollColumn.NONE) {
            updateDraggedScroll(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingScrollColumn != ScrollColumn.NONE) {
            draggingScrollColumn = ScrollColumn.NONE;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
        super.renderBg(graphics, partialTicks, mouseX, mouseY);
        int panelX = getGuiLeft() + PANEL_OFFSET_X;
        int panelY = getGuiTop() + PANEL_OFFSET_Y;
        graphics.blit(PANEL_TEXTURE, panelX, panelY, PANEL_OFFSET_X, PANEL_OFFSET_Y,
                PANEL_WIDTH, PANEL_HEIGHT, 256, 256);
    }

    @Override
    protected void renderAddition(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.renderAddition(graphics, mouseX, mouseY, partialTicks);

        if (renameDebounceFrames > 0) {
            renameDebounceFrames--;
            if (renameDebounceFrames == 0) {
                submitPendingRename();
            }
        }

        AutoWorkSnapshot current = ClientAutoWorkService.get().snapshotOrNull();
        if (current != observedSnapshot) {
            observedSnapshot = current;
            dirty = true;
        }
        if (dirty) {
            rebuildAutoWorkWidgets();
            dirty = false;
        }

        drawColumnTitles(graphics);
        drawPresetCount(graphics);
        drawScrollBars(graphics);
    }

    private void drawColumnTitles(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        int panelX = getGuiLeft() + PANEL_OFFSET_X;
        int panelY = getGuiTop() + PANEL_OFFSET_Y;
        graphics.drawString(mc.font,
                Component.translatable("gui.tlm_sincerely.task.column.available"),
                panelX + LEFT_COLUMN_X, panelY + TITLES_Y, TEXT_COLOR, false);
        graphics.drawString(mc.font,
                Component.translatable("gui.tlm_sincerely.task.column.ordered"),
                panelX + RIGHT_COLUMN_X, panelY + TITLES_Y, TEXT_COLOR, false);
        graphics.drawString(mc.font,
                Component.translatable("gui.tlm_sincerely.task.hint.scroll"),
                panelX + HINT_X, panelY + HINT_Y, TEXT_COLOR, false);
    }

    private void drawPresetCount(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        List<AutoWorkPreset> presets = currentPresets();
        int activeIndex = 0;
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).getId().equals(activePresetId)) {
                activeIndex = i + 1;
                break;
            }
        }
        int panelX = getGuiLeft() + PANEL_OFFSET_X;
        int panelY = getGuiTop() + PANEL_OFFSET_Y;
        graphics.drawString(mc.font,
                Component.translatable("gui.tlm_sincerely.task.preset.count",
                        String.valueOf(activeIndex), String.valueOf(presets.size())),
                panelX + PRESET_COUNT_X, panelY + PRESET_COUNT_Y, TEXT_COLOR, false);
    }

    private void drawScrollBars(GuiGraphics graphics) {
        int panelX = getGuiLeft() + PANEL_OFFSET_X;
        int panelY = getGuiTop() + PANEL_OFFSET_Y;
        int rowsY = panelY + ROWS_TOP;
        int rowsH = rowsHeight();
        drawScrollBar(graphics, panelX + SCROLLBAR_LEFT_X, rowsY, rowsH, availableTaskCount, leftScroll);
        drawScrollBar(graphics, panelX + SCROLLBAR_RIGHT_X, rowsY, rowsH, orderedTaskCount, rightScroll);
    }

    private static void drawScrollBar(GuiGraphics graphics, int x, int y, int height, int taskCount, int scroll) {
        int maxScroll = maxScroll(taskCount);
        if (maxScroll <= 0) {
            return;
        }
        int thumbHeight = scrollThumbHeight(height, taskCount);
        int travel = height - thumbHeight;
        int thumbY = y + Math.round((float) scroll / maxScroll * travel);
        graphics.fill(x, y, x + SCROLLBAR_WIDTH, y + height, SCROLLBAR_TRACK_COLOR);
        graphics.fill(x, thumbY, x + SCROLLBAR_WIDTH, thumbY + thumbHeight, SCROLLBAR_THUMB_COLOR);
    }

    private void updateDraggedScroll(double mouseY) {
        int panelY = getGuiTop() + PANEL_OFFSET_Y;
        int rowsY = panelY + ROWS_TOP;
        int rowsH = rowsHeight();
        int taskCount = draggingScrollColumn == ScrollColumn.LEFT ? availableTaskCount : orderedTaskCount;
        int maxScroll = maxScroll(taskCount);
        if (maxScroll <= 0) {
            return;
        }
        int thumbHeight = scrollThumbHeight(rowsH, taskCount);
        int travel = rowsH - thumbHeight;
        if (travel <= 0) {
            return;
        }
        double relativeY = Math.max(0, Math.min(travel, mouseY - rowsY - thumbHeight / 2.0));
        int scroll = (int) Math.round(relativeY / travel * maxScroll);
        if (draggingScrollColumn == ScrollColumn.LEFT) {
            leftScroll = scroll;
        } else {
            rightScroll = scroll;
        }
        dirty = true;
    }

    private static int rowsHeight() {
        return VISIBLE_ROWS * ROW_HEIGHT;
    }

    private static int scrollThumbHeight(int trackHeight, int taskCount) {
        if (taskCount <= 0) {
            return trackHeight;
        }
        return Math.max(SCROLLBAR_MIN_THUMB_HEIGHT,
                Math.round((float) VISIBLE_ROWS / taskCount * trackHeight));
    }

    // ---------------------------------------------------------------
    // Widget construction / teardown
    // ---------------------------------------------------------------

    private void rebuildAutoWorkWidgets() {
        // Keep a focused rename box alive across rebuilds: rebuilding it
        // would reset the in-progress name and drop the edit focus.
        boolean keepRenameBox = renameBox != null && renameBox.isFocused();
        for (AbstractWidget widget : ownedWidgets) {
            this.removeWidget(widget);
        }
        ownedWidgets.clear();
        if (!keepRenameBox) {
            renameBox = null;
        }

        int panelX = getGuiLeft() + PANEL_OFFSET_X;
        int panelY = getGuiTop() + PANEL_OFFSET_Y;
        int presetY = panelY + PRESET_BUTTON_Y;
        int editY = panelY + PRESET_EDIT_Y;

        // Preset row: < > [EditBox] × +
        addOwned(addRenderableWidget(new AutoWorkBrownButton(
                panelX + LEFT_COLUMN_X, presetY, 12, 13,
                AutoWorkBrownButton.TEXTURE_GUI, 71, 0, 14,
                Component.empty(), button -> cyclePreset(-1))));
        if (renameBox == null) {
            renameBox = new EditBox(Minecraft.getInstance().font,
                    panelX + 31, editY, 57, 15,
                    Component.translatable("gui.tlm_sincerely.task.preset.name"));
            renameBox.setMaxLength(32);
            String presetName = resolveActivePreset().map(AutoWorkPreset::getName).orElse("");
            lastSubmittedRename = presetName;
            renameBox.setValue(presetName);
            renameBox.setResponder(this::onPresetNameChanged);
            renameBox.setFocused(false);
        }
        addOwned(addRenderableWidget(renameBox));

        addOwned(addRenderableWidget(new AutoWorkBrownButton(
                panelX + 18, presetY, 12, 13,
                AutoWorkBrownButton.TEXTURE_GUI, 84, 0, 14,
                Component.empty(), button -> cyclePreset(1))));
        UUID activeId = resolveActivePreset().map(AutoWorkPreset::getId).orElse(null);
        addOwned(addRenderableWidget(new AutoWorkBrownButton(
                panelX + 89, presetY, 13, 13,
                AutoWorkBrownButton.TEXTURE_TASK, 127, 0, 14,
                Component.empty(), button -> deletePreset(activeId))));
        addOwned(addRenderableWidget(new AutoWorkBrownButton(
                panelX + 103, presetY, 13, 13,
                AutoWorkBrownButton.TEXTURE_GUI, 188, 0, 14,
                Component.empty(), button -> createPreset())));

        // Two-column task list
        int leftX = panelX + LEFT_COLUMN_X;
        int rightX = panelX + RIGHT_COLUMN_X;
        int rowsY = panelY + ROWS_TOP;

        List<ResourceLocation> order = resolveActivePreset()
                .map(AutoWorkPreset::getOrder).orElse(List.of());
        List<IMaidTask> ordered = new ArrayList<>();
        for (ResourceLocation taskId : order) {
            TaskManager.findTask(taskId).ifPresent(ordered::add);
        }
        List<IMaidTask> available = new ArrayList<>();
        ResourceLocation idleTaskUid = TaskManager.getIdleTask().getUid();
        for (IMaidTask task : TaskManager.getTaskIndex()) {
            // idle is the "do nothing" fallback, not a real work task, so it
            // must never be part of a preset. Hide it from the addable list;
            // the client library rejects it as well (defense in depth).
            if (!order.contains(task.getUid()) && !task.getUid().equals(idleTaskUid)) {
                available.add(task);
            }
        }
        availableTaskCount = available.size();
        orderedTaskCount = ordered.size();

        leftScroll = clampScroll(leftScroll, available.size());
        rightScroll = clampScroll(rightScroll, ordered.size());
        int selIdx = findSelectedIndex(order);
        if (selIdx < 0 && selectedTaskId != null) {
            // selectedTaskId no longer in the list — clear it
            selectedTaskId = null;
        }
        if (!diagnosticsLogged) {
            long iconCount = available.stream().filter(task -> !task.getIcon().isEmpty()).count()
                    + ordered.stream().filter(task -> !task.getIcon().isEmpty()).count();
            LOGGER.debug("Auto-work editor diagnostics: panel={}x{} at {},{}; available={}, ordered={}, icons={}",
                    PANEL_WIDTH, PANEL_HEIGHT, panelX, panelY, availableTaskCount, orderedTaskCount, iconCount);
            diagnosticsLogged = true;
        }

        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int index = leftScroll + row;
            if (index < available.size()) {
                IMaidTask task = available.get(index);
                UUID presetId = activeId;
                addOwned(addRenderableWidget(new AutoWorkBrownButton(
                        leftX, rowsY + row * ROW_HEIGHT, COLUMN_WIDTH, ROW_HEIGHT,
                        AutoWorkBrownButton.TEXTURE_GUI, 97, 0, 17,
                        Component.literal(task.getName().getString()),
                        button -> {
                            if (presetId != null && AutoWorkClientLibrary.get().addTask(presetId, task.getUid())) {
                                rebakeActivePreset();
                                dirty = true;
                            }
                        }, false, task.getIcon(), false, null)));
            }
        }
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int index = rightScroll + row;
            if (index < ordered.size()) {
                IMaidTask task = ordered.get(index);
                UUID presetId = activeId;
                addOwned(addRenderableWidget(new AutoWorkBrownButton(
                        rightX, rowsY + row * ROW_HEIGHT, COLUMN_WIDTH, ROW_HEIGHT,
                        AutoWorkBrownButton.TEXTURE_GUI, 97, 0, 17,
                        Component.literal(task.getName().getString()),
                        button -> {
                            selectedTaskId = task.getUid();
                            dirty = true;
                        }, false, task.getIcon(), task.getUid().equals(selectedTaskId),
                        button -> removePresetTask(presetId, task.getUid()))));
            }
        }

        int controlsY = panelY + CONTROLS_TOP;
        addOwned(addRenderableWidget(new AutoWorkBrownButton(
                panelX + 115, controlsY, 16, 13,
                AutoWorkBrownButton.TEXTURE_TASK, 110, 0, 14,
                Component.empty(), button -> moveSelected(-1))));
        addOwned(addRenderableWidget(new AutoWorkBrownButton(
                panelX + 132, controlsY, 16, 13,
                AutoWorkBrownButton.TEXTURE_TASK, 93, 0, 14,
                Component.empty(), button -> moveSelected(1))));
        addOwned(addRenderableWidget(new AutoWorkBrownButton(
                panelX + 149, controlsY, 13, 13,
                AutoWorkBrownButton.TEXTURE_TASK, 127, 0, 14,
                Component.empty(), button -> removeSelected())));
    }

    private void addOwned(AbstractWidget widget) {
        if (widget != null) {
            ownedWidgets.add(widget);
        }
    }

    // ---------------------------------------------------------------
    // Snapshot / preset helpers
    // ---------------------------------------------------------------

    private java.util.Optional<AutoWorkSnapshot.MaidEntry> resolveMaidEntry() {
        var maid = getMenu().getMaid();
        if (maid == null) {
            return java.util.Optional.empty();
        }
        return ClientAutoWorkService.get().findMaid(maid.getUUID());
    }

    private UUID resolveInitialPreset() {
        AutoWorkClientLibrary library = AutoWorkClientLibrary.get();
        return resolveMaidEntry()
                .map(AutoWorkSnapshot.MaidEntry::presetId)
                .filter(id -> library.getPreset(id) != null)
                .orElseGet(library::defaultPresetId);
    }

    private java.util.Optional<AutoWorkPreset> resolveActivePreset() {
        AutoWorkClientLibrary library = AutoWorkClientLibrary.get();
        AutoWorkPreset preset = library.getPreset(activePresetId);
        if (preset != null) {
            return java.util.Optional.of(preset);
        }
        List<AutoWorkPreset> presets = currentPresets();
        if (!presets.isEmpty()) {
            activePresetId = presets.get(0).getId();
            return java.util.Optional.of(presets.get(0));
        }
        return java.util.Optional.empty();
    }

    private List<AutoWorkPreset> currentPresets() {
        return AutoWorkClientLibrary.get().presetsInOrder();
    }

    // ---------------------------------------------------------------
    // Preset / task actions (all client-local)
    // ---------------------------------------------------------------

    /** Writes the active library preset onto this maid's bound snapshot. */
    private void rebakeActivePreset() {
        resolveActivePreset().ifPresent(this::applyPresetToMaid);
    }

    /**
     * Cycles the selected preset and re-bakes it onto the maid.
     */
    private void cyclePreset(int delta) {
        List<AutoWorkPreset> presets = currentPresets();
        if (presets.isEmpty()) {
            return;
        }
        int current = 0;
        for (int index = 0; index < presets.size(); index++) {
            if (presets.get(index).getId().equals(activePresetId)) {
                current = index;
                break;
            }
        }
        AutoWorkPreset next = presets.get(Math.floorMod(current + delta, presets.size()));
        activePresetId = next.getId();
        selectedTaskId = null;
        applyPresetToMaid(next);
        dirty = true;
    }

    private void createPreset() {
        String base = Component.translatable("gui.tlm_sincerely.task.preset.default_name").getString();
        AutoWorkPreset created = AutoWorkClientLibrary.get().createPreset(base);
        activePresetId = created.getId();
        selectedTaskId = null;
        applyPresetToMaid(created);
        dirty = true;
    }

    /** Sends the full snapshot; the server re-bakes the maid on every call. */
    private void applyPresetToMaid(AutoWorkPreset preset) {
        var maid = getMenu().getMaid();
        if (maid == null) {
            return;
        }
        AutoWorkNetworking.channel().sendToServer(new SetMaidAutoWorkPresetC2SPacket(
                maid.getUUID(), preset.getId(), preset.getName(), preset.getOrder()));
    }

    /** Debounced rename: only writes to the local library after a short frame delay. */
    private void onPresetNameChanged(String value) {
        String trimmed = value.trim();
        if (trimmed.equals(lastSubmittedRename)) {
            return;
        }
        pendingRename = trimmed;
        renameDebounceFrames = RENAME_DEBOUNCE_FRAMES;
    }

    private void submitPendingRename() {
        String trimmed = pendingRename.trim();
        if (trimmed.isEmpty() || trimmed.equals(lastSubmittedRename)) {
            return;
        }
        UUID activeId = resolveActivePreset().map(AutoWorkPreset::getId).orElse(null);
        if (activeId != null && AutoWorkClientLibrary.get().renamePreset(activeId, trimmed)) {
            rebakeActivePreset();
            dirty = true;
        }
        lastSubmittedRename = trimmed;
    }

    private void deletePreset(UUID presetId) {
        if (presetId != null && AutoWorkClientLibrary.get().deletePreset(presetId)) {
            activePresetId = AutoWorkClientLibrary.get().defaultPresetId();
            selectedTaskId = null;
            rebakeActivePreset();
            dirty = true;
        }
    }

    private void moveSelected(int delta) {
        AutoWorkPreset active = resolveActivePreset().orElse(null);
        if (active == null || selectedTaskId == null) {
            return;
        }
        List<ResourceLocation> order = active.getOrder();
        int idx = order.indexOf(selectedTaskId);
        if (idx < 0) {
            return;
        }
        int target = Math.max(0, Math.min(idx + delta, order.size() - 1));
        if (target != idx && AutoWorkClientLibrary.get().moveTask(active.getId(), selectedTaskId, target)) {
            rebakeActivePreset();
            dirty = true;
        }
    }

    private void removeSelected() {
        AutoWorkPreset active = resolveActivePreset().orElse(null);
        if (active == null || selectedTaskId == null) {
            return;
        }
        if (AutoWorkClientLibrary.get().removeTask(active.getId(), selectedTaskId)) {
            selectedTaskId = null;
            rebakeActivePreset();
            dirty = true;
        }
    }

    private void removePresetTask(UUID presetId, ResourceLocation taskId) {
        if (presetId == null) {
            return;
        }
        if (AutoWorkClientLibrary.get().removeTask(presetId, taskId)) {
            if (taskId.equals(selectedTaskId)) {
                selectedTaskId = null;
            }
            rebakeActivePreset();
            dirty = true;
            LOGGER.debug("Removed auto-work task from local library: preset={}, task={}", presetId, taskId);
        }
    }

    private static int clampScroll(int scroll, int taskCount) {
        return Math.max(0, Math.min(scroll, maxScroll(taskCount)));
    }

    private static int maxScroll(int taskCount) {
        return Math.max(0, taskCount - VISIBLE_ROWS);
    }

    private static boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** Returns the index of {@link #selectedTaskId} inside the given order,
     *  or {@code -1} if none matches. */
    private int findSelectedIndex(List<ResourceLocation> order) {
        if (selectedTaskId == null) {
            return -1;
        }
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).equals(selectedTaskId)) {
                return i;
            }
        }
        return -1;
    }

    private enum ScrollColumn {
        NONE,
        LEFT,
        RIGHT
    }
}
