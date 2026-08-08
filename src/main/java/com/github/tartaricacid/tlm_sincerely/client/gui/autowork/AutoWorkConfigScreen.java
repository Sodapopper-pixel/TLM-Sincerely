package com.github.tartaricacid.tlm_sincerely.client.gui.autowork;

import com.github.tartaricacid.tlm_sincerely.client.network.ClientAutoWorkService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.menu.AutoWorkConfigContainer;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkNetworking;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkSnapshot;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AddPresetTaskC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.CreatePresetC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.DeletePresetC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.MovePresetTaskC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.RemovePresetTaskC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.RenamePresetC2SPacket;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.SetMaidAutoWorkPresetC2SPacket;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Independent auto work configuration screen, opened when the player
 * requests the standalone auto work container (see
 * {@code OpenAutoWorkConfigC2SPacket}).
 *
 * <p>This screen extends TLM's {@link AbstractMaidContainerGui} so we inherit
 * the full maid base layout (portrait, schedule button,
 * tabs, task list, inventory, …) and then renders the auto work editor
 * <em>only</em> on top of the right-side configuration rectangle
 * (TLM's "task config" region). The rectangle position matches the
 * TLM {@code DefaultMaidTaskConfigGui}: {@code leftPos + 80},
 * {@code topPos + 28}, with a fixed size of {@code 176x137}. Per the
 * project policy, the width is hard-coded; we never call
 * {@code getXSize()} for that area.
 *
 * <p>The editor mirrors the legacy overlay's C2S interactions: preset
 * prev/next, name edit + confirm, new, delete, two-column available /
 * ordered task list with click-to-add, click-to-select, scroll-wheel
 * scroll on each column, and bottom up / down / remove buttons. The
 * screen uses {@code addRenderableWidget} so its own widgets block
 * clicks (no pass-through to TLM widgets underneath), and it removes
 * only the widgets it owns on snapshot updates so TLM's base widgets
 * are preserved.
 */
public class AutoWorkConfigScreen extends AbstractMaidContainerGui<AutoWorkConfigContainer> {

    /** TLM DefaultMaidTaskConfigGui right-side rectangle. Hard-coded per project policy. */
    private static final int PANEL_OFFSET_X = 80;
    private static final int PANEL_OFFSET_Y = 28;
    private static final int PANEL_WIDTH = 176;
    private static final int PANEL_HEIGHT = 137;
    private static final ResourceLocation DEFAULT_TASK_CONFIG_BG = new ResourceLocation(
            "touhou_little_maid", "textures/gui/default_task_config.png");

    /** Sub-layout constants inside the panel. */
    private static final int ROW_HEIGHT = 16;
    private static final int VISIBLE_ROWS = 4;
    private static final int ROWS_TOP = 53;
    private static final int CONTROLS_BOTTOM_OFFSET = 4;
    private static final int CONTROLS_BUTTON_SIZE = 13;
    private static final int PRESET_ROW_Y = 23;

    /** The widgets this screen owns, tracked so we can remove only our own on rebuild. */
    private final List<AbstractWidget> ownedWidgets = new ArrayList<>();

    /** Listener for {@link ClientAutoWorkService} snapshot updates. */
    private final Consumer<AutoWorkSnapshot> snapshotListener = snapshot -> dirty = true;

    // Cached state that depends on the current snapshot.
    private AutoWorkSnapshot observedSnapshot;
    private UUID activePresetId;
    private boolean dirty = true;
    private boolean snapshotListenerRegistered;

    // Local UI state.
    private int leftScroll;
    private int rightScroll;
    private int selectedOrderedIndex = -1;
    private EditBox renameBox;

    public AutoWorkConfigScreen(AutoWorkConfigContainer menu, net.minecraft.world.entity.player.Inventory inv,
                                Component title) {
        super(menu, inv, title);
        // imageWidth/imageHeight are inherited from the TLM base
        // class (256x200, the standard maid task config size). No
        // need to override: PANEL_WIDTH is intentionally hard-coded
        // and decoupled from imageWidth.
    }

    @Override
    protected void init() {
        // Let TLM build its full base layout first: portrait, tabs,
        // schedule button, task list, inventory, etc. We only add our
        // own widgets on top afterwards.
        super.init();
        ClientAutoWorkService.get().requestRefresh();
        observedSnapshot = ClientAutoWorkService.get().snapshotOrNull();
        if (observedSnapshot != null) {
            resolveMaidEntry().ifPresent(entry -> activePresetId = entry.presetId());
        }
        if (!snapshotListenerRegistered) {
            ClientAutoWorkService.get().addListener(snapshotListener);
            snapshotListenerRegistered = true;
        }
        rebuildAutoWorkWidgets();
    }

    @Override
    public void removed() {
        if (snapshotListenerRegistered) {
            ClientAutoWorkService.get().removeListener(snapshotListener);
            snapshotListenerRegistered = false;
        }
        super.removed();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        // We only intercept scroll events while the cursor is inside
        // the editor rectangle; everything else (TLM's task list, …)
        // still receives the scroll as before.
        int panelX = getGuiLeft() + PANEL_OFFSET_X;
        int panelY = getGuiTop() + PANEL_OFFSET_Y;
        int rowsY = panelY + ROWS_TOP;
        int rowsH = VISIBLE_ROWS * ROW_HEIGHT;
        int leftX = panelX + 5;
        int rightX = panelX + PANEL_WIDTH / 2 + 2;
        int columnW = PANEL_WIDTH / 2 - 9;
        int delta = scrollDelta > 0 ? -1 : 1;
        if (isInside(mouseX, mouseY, leftX, rowsY, columnW, rowsH)) {
            leftScroll = Math.max(0, leftScroll + delta);
            dirty = true;
            return true;
        }
        if (isInside(mouseX, mouseY, rightX, rowsY, columnW, rowsH)) {
            rightScroll = Math.max(0, rightScroll + delta);
            dirty = true;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollDelta);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
        super.renderBg(graphics, partialTicks, mouseX, mouseY);
        // The base maid GUI does not draw the default task
        // panel itself. Reuse the exact TLM panel texture at its documented
        // 176x137 bounds instead of accidentally stretching to imageWidth.
        graphics.blit(DEFAULT_TASK_CONFIG_BG,
                getGuiLeft() + PANEL_OFFSET_X, getGuiTop() + PANEL_OFFSET_Y,
                0, 0, PANEL_WIDTH, PANEL_HEIGHT);
    }

    @Override
    protected void renderAddition(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // Important: call super first so TLM's additions (task title,
        // tabs, etc.) remain visible.
        super.renderAddition(graphics, mouseX, mouseY, partialTicks);

        // Detect snapshot changes and rebuild our widgets as needed.
        AutoWorkSnapshot current = ClientAutoWorkService.get().snapshotOrNull();
        if (current != observedSnapshot) {
            observedSnapshot = current;
            if (activePresetId == null || !containsPreset(activePresetId)) {
                resolveMaidEntry().ifPresent(entry -> activePresetId = entry.presetId());
            }
            dirty = true;
        }
        if (dirty) {
            rebuildAutoWorkWidgets();
            dirty = false;
        }

        drawColumnTitles(graphics);
    }

    private void drawColumnTitles(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        int panelX = getGuiLeft() + PANEL_OFFSET_X;
        int panelY = getGuiTop() + PANEL_OFFSET_Y;
        graphics.drawString(mc.font,
                Component.translatable("gui.tlm_sincerely.task.column.available"),
                panelX + 5, panelY + 42, 0xFF1A1A1A, false);
        graphics.drawString(mc.font,
                Component.translatable("gui.tlm_sincerely.task.column.ordered"),
                panelX + PANEL_WIDTH / 2 + 2, panelY + 42, 0xFF1A1A1A, false);
        graphics.drawString(mc.font,
                Component.translatable("gui.tlm_sincerely.task.hint.scroll"),
                panelX + 5, panelY + PANEL_HEIGHT - CONTROLS_BOTTOM_OFFSET - 6, 0xFF555555, false);
    }

    // ---------------------------------------------------------------
    // Widget construction / teardown
    // ---------------------------------------------------------------

    private void rebuildAutoWorkWidgets() {
        // Remove only the widgets this screen owns. removeWidget
        // requires the screen children list; AbstractContainerScreen
        // stores them in `children`, accessible via renderable lists.
        for (AbstractWidget widget : ownedWidgets) {
            this.removeWidget(widget);
        }
        ownedWidgets.clear();
        renameBox = null;

        int panelX = getGuiLeft() + PANEL_OFFSET_X;
        int panelY = getGuiTop() + PANEL_OFFSET_Y;
        int presetY = panelY + PRESET_ROW_Y;

        // Preset prev / name / next / new / delete / rename
        addOwned(addRenderableWidget(new AutoWorkBrownButton(
                panelX + 5, presetY, 13, 15,
                Component.literal("<"), button -> cyclePreset(-1), true)));
        renameBox = new EditBox(Minecraft.getInstance().font,
                panelX + 20, presetY, 85, 15,
                Component.translatable("gui.tlm_sincerely.task.preset.name"));
        renameBox.setMaxLength(32);
        renameBox.setValue(resolveActivePreset().map(AutoWorkPreset::name).orElse(""));
        renameBox.setFocused(false);
        addOwned(addRenderableWidget(renameBox));

        addOwned(addRenderableWidget(new AutoWorkBrownButton(
                panelX + 107, presetY, 13, 15,
                Component.literal(">"), button -> cyclePreset(1), true)));
        addOwned(addRenderableWidget(new AutoWorkBrownButton(
                panelX + 122, presetY, 14, 15,
                Component.literal("+"), button -> createPreset(), true)));
        UUID activeId = resolveActivePreset().map(AutoWorkPreset::id).orElse(null);
        addOwned(addRenderableWidget(new AutoWorkBrownButton(
                panelX + 138, presetY, 14, 15,
                Component.literal("×"), button -> deletePreset(activeId), true)));
        addOwned(addRenderableWidget(new AutoWorkBrownButton(
                panelX + 154, presetY, 17, 15,
                Component.literal("✓"), button -> renamePreset(activeId), true)));

        // Two-column task list
        int leftX = panelX + 5;
        int rightX = panelX + PANEL_WIDTH / 2 + 2;
        int columnW = PANEL_WIDTH / 2 - 9;
        int rowsY = panelY + ROWS_TOP;

        AutoWorkPreset active = resolveActivePreset().orElse(null);
        List<ResourceLocation> order = active == null ? List.of() : active.order();
        List<IMaidTask> ordered = new ArrayList<>();
        for (ResourceLocation taskId : order) {
            TaskManager.findTask(taskId).ifPresent(ordered::add);
        }
        List<IMaidTask> available = new ArrayList<>();
        for (IMaidTask task : TaskManager.getTaskIndex()) {
            if (!order.contains(task.getUid())) {
                available.add(task);
            }
        }

        leftScroll = clampScroll(leftScroll, available.size());
        rightScroll = clampScroll(rightScroll, ordered.size());
        if (selectedOrderedIndex >= ordered.size()) {
            selectedOrderedIndex = ordered.isEmpty() ? -1 : ordered.size() - 1;
        }

        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int index = leftScroll + row;
            if (index < available.size()) {
                IMaidTask task = available.get(index);
                UUID presetId = activeId;
                addOwned(addRenderableWidget(new AutoWorkBrownButton(
                        leftX, rowsY + row * ROW_HEIGHT, columnW, ROW_HEIGHT,
                        Component.literal("+ " + task.getName().getString()),
                        button -> {
                            if (presetId != null) {
                                AutoWorkNetworking.channel().sendToServer(
                                        new AddPresetTaskC2SPacket(presetId, task.getUid()));
                            }
                        }, false)));
            }
        }
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int index = rightScroll + row;
            if (index < ordered.size()) {
                IMaidTask task = ordered.get(index);
                String prefix = index == selectedOrderedIndex ? "› " : "  ";
                int selectedIndex = index;
                addOwned(addRenderableWidget(new AutoWorkBrownButton(
                        rightX, rowsY + row * ROW_HEIGHT, columnW, ROW_HEIGHT,
                        Component.literal(prefix + task.getName().getString()),
                        button -> {
                            selectedOrderedIndex = selectedIndex;
                            dirty = true;
                        }, false)));
            }
        }

        int controlsY = panelY + PANEL_HEIGHT - CONTROLS_BOTTOM_OFFSET - CONTROLS_BUTTON_SIZE;
        addOwned(addRenderableWidget(new AutoWorkBrownButton(
                rightX + columnW - 44, controlsY, CONTROLS_BUTTON_SIZE, CONTROLS_BUTTON_SIZE,
                Component.literal("↑"), button -> moveSelected(-1), true)));
        addOwned(addRenderableWidget(new AutoWorkBrownButton(
                rightX + columnW - 29, controlsY, CONTROLS_BUTTON_SIZE, CONTROLS_BUTTON_SIZE,
                Component.literal("↓"), button -> moveSelected(1), true)));
        addOwned(addRenderableWidget(new AutoWorkBrownButton(
                rightX + columnW - 14, controlsY, CONTROLS_BUTTON_SIZE, CONTROLS_BUTTON_SIZE,
                Component.literal("×"), button -> removeSelected(), true)));
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

    private boolean containsPreset(UUID presetId) {
        return ClientAutoWorkService.get().findPreset(presetId).isPresent();
    }

    private java.util.Optional<AutoWorkPreset> resolveActivePreset() {
        List<AutoWorkPreset> presets = currentPresets();
        for (AutoWorkPreset preset : presets) {
            if (preset.id().equals(activePresetId)) {
                return java.util.Optional.of(preset);
            }
        }
        if (!presets.isEmpty()) {
            activePresetId = presets.get(0).id();
            return java.util.Optional.of(presets.get(0));
        }
        return java.util.Optional.empty();
    }

    private List<AutoWorkPreset> currentPresets() {
        List<AutoWorkPreset> presets = new ArrayList<>();
        for (AutoWorkSnapshot.PresetEntry entry : ClientAutoWorkService.get().presets()) {
            presets.add(new AutoWorkPreset(entry.id(), entry.name(), entry.order()));
        }
        return presets;
    }

    // ---------------------------------------------------------------
    // C2S actions
    // ---------------------------------------------------------------

    private void cyclePreset(int delta) {
        List<AutoWorkPreset> presets = currentPresets();
        if (presets.isEmpty()) {
            return;
        }
        int current = 0;
        for (int index = 0; index < presets.size(); index++) {
            if (presets.get(index).id().equals(activePresetId)) {
                current = index;
                break;
            }
        }
        UUID next = presets.get(Math.floorMod(current + delta, presets.size())).id();
        var maid = getMenu().getMaid();
        if (maid != null) {
            AutoWorkNetworking.channel().sendToServer(
                    new SetMaidAutoWorkPresetC2SPacket(maid.getUUID(), next));
        }
        activePresetId = next;
        selectedOrderedIndex = -1;
        dirty = true;
    }

    private void createPreset() {
        String base = Component.translatable("gui.tlm_sincerely.task.preset.default_name").getString();
        Set<String> taken = new HashSet<>();
        for (AutoWorkPreset preset : currentPresets()) {
            taken.add(preset.name().toLowerCase(Locale.ROOT));
        }
        String name = base;
        for (int suffix = 2; taken.contains(name.toLowerCase(Locale.ROOT)); suffix++) {
            name = base + " " + suffix;
        }
        AutoWorkNetworking.channel().sendToServer(new CreatePresetC2SPacket(name));
    }

    private void renamePreset(UUID presetId) {
        if (presetId == null || renameBox == null) {
            return;
        }
        String name = renameBox.getValue().trim();
        if (!name.isEmpty()) {
            AutoWorkNetworking.channel().sendToServer(new RenamePresetC2SPacket(presetId, name));
        }
    }

    private void deletePreset(UUID presetId) {
        if (presetId != null) {
            AutoWorkNetworking.channel().sendToServer(new DeletePresetC2SPacket(presetId));
        }
    }

    private void moveSelected(int delta) {
        AutoWorkPreset active = resolveActivePreset().orElse(null);
        if (active == null || selectedOrderedIndex < 0
                || selectedOrderedIndex >= active.order().size()) {
            return;
        }
        int target = Math.max(0,
                Math.min(selectedOrderedIndex + delta, active.order().size() - 1));
        if (target != selectedOrderedIndex) {
            AutoWorkNetworking.channel().sendToServer(new MovePresetTaskC2SPacket(
                    active.id(), active.order().get(selectedOrderedIndex), target));
            selectedOrderedIndex = target;
        }
    }

    private void removeSelected() {
        AutoWorkPreset active = resolveActivePreset().orElse(null);
        if (active == null || selectedOrderedIndex < 0
                || selectedOrderedIndex >= active.order().size()) {
            return;
        }
        AutoWorkNetworking.channel().sendToServer(new RemovePresetTaskC2SPacket(
                active.id(), active.order().get(selectedOrderedIndex)));
        selectedOrderedIndex = -1;
    }

    private static int clampScroll(int scroll, int taskCount) {
        return Math.max(0, Math.min(scroll, Math.max(0, taskCount - VISIBLE_ROWS)));
    }

    private static boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /**
     * Internal view of a snapshot preset. Decoupled from
     * {@link AutoWorkSnapshot.PresetEntry} so the editor code is
     * identical whether it works on a snapshot entry or a copy.
     */
    private record AutoWorkPreset(UUID id, String name, List<ResourceLocation> order) {
    }
}
