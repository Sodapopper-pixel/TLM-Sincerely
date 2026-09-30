package com.github.tartaricacid.tlm_sincerely.priority.autowork;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Work order preset for the auto work switch.
 *
 * <p>Presets live in each client's private library and are copied into a
 * maid's bound snapshot when selected; the server only holds the frozen seed
 * used for legacy migration and the first-join copy.
 *
 * <p>This class has no numeric priority field; the {@link #order()} list IS
 * the priority. Index 0 is the highest priority.
 *
 * <p>{@code id} is a stable UUID used to reference the preset from
 * {@link AutoWorkState#presetId()}; name is only for display.
 */
public final class AutoWorkPreset {
    private final UUID id;
    private String name;
    private final List<ResourceLocation> order;

    public AutoWorkPreset(UUID id, String name, List<ResourceLocation> order) {
        this.id = id;
        this.name = name;
        this.order = new ArrayList<>(order);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<ResourceLocation> getOrder() {
        return order;
    }

    public boolean hasTask(ResourceLocation taskId) {
        return order.contains(taskId);
    }

    /** Adds the task to the end of the order if absent. */
    public boolean addTask(ResourceLocation taskId) {
        if (order.contains(taskId)) {
            return false;
        }
        return order.add(taskId);
    }

    public boolean removeTask(ResourceLocation taskId) {
        return order.remove(taskId);
    }

    /** Returns a defensive copy of the order list. */
    public List<ResourceLocation> getSortedTasks() {
        return new ArrayList<>(order);
    }

    /**
     * Moves the task within the order list. Index is clamped to
     * {@code [0, size - 1]}; absent tasks are not added.
     */
    public void moveTask(ResourceLocation taskId, int targetIndex) {
        int currentIndex = order.indexOf(taskId);
        if (currentIndex < 0) {
            return;
        }
        int clamped = Math.max(0, Math.min(targetIndex, order.size() - 1));
        if (clamped == currentIndex) {
            return;
        }
        order.remove(currentIndex);
        order.add(clamped, taskId);
    }

    public void moveTaskUp(ResourceLocation taskId) {
        int index = order.indexOf(taskId);
        if (index > 0) {
            order.remove(index);
            order.add(index - 1, taskId);
        }
    }

    public void moveTaskDown(ResourceLocation taskId) {
        int index = order.indexOf(taskId);
        if (index >= 0 && index < order.size() - 1) {
            order.remove(index);
            order.add(index + 1, taskId);
        }
    }
}
