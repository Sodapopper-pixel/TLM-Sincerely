package com.github.tartaricacid.tlm_sincerely.priority;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TaskPriorityPreset {
    private String name;
    private final Map<ResourceLocation, Integer> priorities;
    private final List<ResourceLocation> order;

    public TaskPriorityPreset() {
        this("default");
    }

    public TaskPriorityPreset(String name) {
        this.name = name;
        this.priorities = new LinkedHashMap<>();
        this.order = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<ResourceLocation, Integer> getPriorities() {
        return priorities;
    }

    public List<ResourceLocation> getOrder() {
        return order;
    }

    public void setPriority(ResourceLocation taskId, int priority) {
        if (priority < 1 || priority > 10) {
            throw new IllegalArgumentException("Priority must be between 1 and 10");
        }
        priorities.put(taskId, priority);
        if (!order.contains(taskId)) {
            order.add(taskId);
        }
    }

    void setPriorityNoReorder(ResourceLocation taskId, int priority) {
        if (priority < 1 || priority > 10) {
            throw new IllegalArgumentException("Priority must be between 1 and 10");
        }
        priorities.put(taskId, priority);
    }

    public void removeTask(ResourceLocation taskId) {
        priorities.remove(taskId);
        order.remove(taskId);
    }

    public boolean hasTask(ResourceLocation taskId) {
        return priorities.containsKey(taskId);
    }

    public List<ResourceLocation> getSortedTasks() {
        return new ArrayList<>(order);
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

    @Override
    public String toString() {
        return "TaskPriorityPreset{name='" + name + "', tasks=" + priorities.size() + "}";
    }
}
