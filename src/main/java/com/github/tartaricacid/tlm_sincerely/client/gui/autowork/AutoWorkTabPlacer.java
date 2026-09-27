package com.github.tartaricacid.tlm_sincerely.client.gui.autowork;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds an overlap-free slot for the auto-work top tab on TLM maid GUIs.
 *
 * <p>Third-party add-ons may append their own top tabs to
 * {@code MaidTabs#getTabs} at the same "fourth slot" position we use, or
 * add widgets through {@code MaidContainerGuiEvent.Init}. To stay usable
 * in both cases the tab is placed by probing candidate slots in reading
 * order — rightwards along TLM's tab row first, then wrapping down one
 * row as a last resort — against the rectangles of every other visible
 * widget already present.
 *
 * <p>Upwards is not an option: the tab row already sits at
 * {@code topPos + 5}, flush with the GUI's top border, so there is no
 * space above to retreat into.
 */
public final class AutoWorkTabPlacer {
    /** TLM's tab row origin: MaidTabs places the first tab at leftPos + 94. */
    private static final int TAB_ROW_ORIGIN_X = 94;
    private static final int TAB_ROW_Y_OFFSET = 5;
    /** TLM's own spacing between neighbouring tabs. */
    private static final int TAB_SPACING = 25;
    /** Our tab starts probing after TLM's three built-in tabs. */
    private static final int START_SLOT = 3;
    private static final int SLOTS_PER_LINE = 6;
    private static final int EXTRA_LINES = 1;
    /** MaidTabButton is 24x26; +2px gap so a wrapped row clears the first. */
    private static final int TAB_WIDTH = 24;
    private static final int TAB_HEIGHT = 26;
    private static final int ROW_WRAP_HEIGHT = TAB_HEIGHT + 2;

    private AutoWorkTabPlacer() {
    }

    /** Axis-aligned rectangle used for overlap probing. */
    public record Rect(int x, int y, int width, int height) {
        public boolean overlaps(Rect other) {
            return x < other.x + other.width && x + width > other.x
                    && y < other.y + other.height && y + height > other.y;
        }
    }

    /**
     * Collects rectangles of all visible, non-empty widgets on the screen
     * except {@code exclude} (our own tab, so it never blocks itself).
     */
    public static List<Rect> collectObstacles(Screen screen, AbstractWidget exclude) {
        List<Rect> obstacles = new ArrayList<>();
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget widget && widget != exclude
                    && widget.visible && widget.getWidth() > 0 && widget.getHeight() > 0) {
                obstacles.add(new Rect(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()));
            }
        }
        return obstacles;
    }

    /**
     * Returns {@code true} when {@code rect} intersects any obstacle.
     */
    public static boolean overlapsAny(Rect rect, List<Rect> obstacles) {
        for (Rect obstacle : obstacles) {
            if (rect.overlaps(obstacle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Probes candidate slots in reading order and returns the first
     * overlap-free {@code (x, y)}, or {@code null} when every probe
     * collides (the caller should then keep the tab where it is rather
     * than push it out of the GUI).
     *
     * @param guiLeft GUI left position ({@code screen.getGuiLeft()})
     * @param guiTop  GUI top position ({@code screen.getGuiTop()})
     */
    public static int[] findFreeSlot(int guiLeft, int guiTop, List<Rect> obstacles) {
        for (int line = 0; line <= EXTRA_LINES; line++) {
            int y = guiTop + TAB_ROW_Y_OFFSET + line * ROW_WRAP_HEIGHT;
            for (int slot = START_SLOT; slot < START_SLOT + SLOTS_PER_LINE; slot++) {
                int x = guiLeft + TAB_ROW_ORIGIN_X + slot * TAB_SPACING;
                Rect candidate = new Rect(x, y, TAB_WIDTH, TAB_HEIGHT);
                if (!overlapsAny(candidate, obstacles)) {
                    return new int[]{x, y};
                }
            }
        }
        return null;
    }
}
