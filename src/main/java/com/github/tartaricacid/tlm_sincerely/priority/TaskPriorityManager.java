package com.github.tartaricacid.tlm_sincerely.priority;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TaskPriorityManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve("tlm_sincerely");
    private static final String PRESETS_FILE = "task_priority_presets.json";

    private static final Map<String, TaskPriorityPreset> presets = new LinkedHashMap<>();
    private static String activePresetName = "";
    private static boolean loaded = false;

    private TaskPriorityManager() {
    }

    public static void loadPresets() {
        if (loaded) {
            return;
        }
        loaded = true;

        File file = CONFIG_DIR.resolve(PRESETS_FILE).toFile();
        if (!file.exists()) {
            createDefaultPreset();
            return;
        }

        try {
            String json = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (root.has("active")) {
                activePresetName = root.get("active").getAsString();
            }

            if (root.has("presets")) {
                JsonArray presetsArray = root.getAsJsonArray("presets");
                for (int i = 0; i < presetsArray.size(); i++) {
                    JsonObject presetObj = presetsArray.get(i).getAsJsonObject();
                    TaskPriorityPreset preset = deserializePreset(presetObj);
                    if (preset != null) {
                        presets.put(preset.getName(), preset);
                    }
                }
            }

            if (presets.isEmpty()) {
                createDefaultPreset();
            }

            if (activePresetName.isEmpty() || !presets.containsKey(activePresetName)) {
                activePresetName = presets.keySet().iterator().next();
            }
        } catch (IOException e) {
            createDefaultPreset();
        }
    }

    public static void savePresets() {
        File file = CONFIG_DIR.resolve(PRESETS_FILE).toFile();
        CONFIG_DIR.toFile().mkdirs();

        JsonObject root = new JsonObject();
        root.addProperty("active", activePresetName);

        JsonArray presetsArray = new JsonArray();
        for (TaskPriorityPreset preset : presets.values()) {
            presetsArray.add(serializePreset(preset));
        }
        root.add("presets", presetsArray);

        try {
            FileUtils.writeStringToFile(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public static List<String> getPresetNames() {
        return new ArrayList<>(presets.keySet());
    }

    public static TaskPriorityPreset getPreset(String name) {
        return presets.get(name);
    }

    public static TaskPriorityPreset getActivePreset() {
        loadPresets();
        TaskPriorityPreset preset = presets.get(activePresetName);
        if (preset == null && !presets.isEmpty()) {
            activePresetName = presets.keySet().iterator().next();
            preset = presets.get(activePresetName);
        }
        return preset;
    }

    public static String getActivePresetName() {
        loadPresets();
        return activePresetName;
    }

    public static void setActivePreset(String name) {
        if (presets.containsKey(name)) {
            activePresetName = name;
            savePresets();
        }
    }

    public static void addPreset(TaskPriorityPreset preset) {
        presets.put(preset.getName(), preset);
        savePresets();
    }

    public static void removePreset(String name) {
        if (presets.size() <= 1) {
            return;
        }
        presets.remove(name);
        if (activePresetName.equals(name)) {
            activePresetName = presets.keySet().iterator().next();
        }
        savePresets();
    }

    public static void renamePreset(String oldName, String newName) {
        TaskPriorityPreset preset = presets.remove(oldName);
        if (preset != null) {
            preset.setName(newName);
            presets.put(newName, preset);
            if (activePresetName.equals(oldName)) {
                activePresetName = newName;
            }
            savePresets();
        }
    }

    public static int getTaskPriority(ResourceLocation taskId) {
        TaskPriorityPreset preset = getActivePreset();
        if (preset == null) {
            return 0;
        }
        return preset.getPriorities().getOrDefault(taskId, 0);
    }

    public static void setTaskPriority(ResourceLocation taskId, int priority) {
        TaskPriorityPreset preset = getActivePreset();
        if (preset == null) {
            return;
        }
        preset.setPriority(taskId, priority);
        savePresets();
    }

    public static void removeTaskPriority(ResourceLocation taskId) {
        TaskPriorityPreset preset = getActivePreset();
        if (preset == null) {
            return;
        }
        preset.removeTask(taskId);
        savePresets();
    }

    private static void createDefaultPreset() {
        TaskPriorityPreset defaultPreset = new TaskPriorityPreset("默认预设");
        presets.put(defaultPreset.getName(), defaultPreset);
        activePresetName = defaultPreset.getName();
        savePresets();
    }

    private static JsonObject serializePreset(TaskPriorityPreset preset) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", preset.getName());

        JsonArray prioritiesArr = new JsonArray();
        for (Map.Entry<ResourceLocation, Integer> entry : preset.getPriorities().entrySet()) {
            JsonObject item = new JsonObject();
            item.addProperty("task", entry.getKey().toString());
            item.addProperty("priority", entry.getValue());
            prioritiesArr.add(item);
        }
        obj.add("priorities", prioritiesArr);

        JsonArray orderArr = new JsonArray();
        for (ResourceLocation id : preset.getOrder()) {
            orderArr.add(id.toString());
        }
        obj.add("order", orderArr);

        return obj;
    }

    private static TaskPriorityPreset deserializePreset(JsonObject obj) {
        if (!obj.has("name")) {
            return null;
        }

        String name = obj.get("name").getAsString();
        TaskPriorityPreset preset = new TaskPriorityPreset(name);

        if (obj.has("order")) {
            JsonArray arr = obj.getAsJsonArray("order");
            for (int i = 0; i < arr.size(); i++) {
                preset.getOrder().add(new ResourceLocation(arr.get(i).getAsString()));
            }
        }

        if (obj.has("priorities")) {
            JsonArray arr = obj.getAsJsonArray("priorities");
            for (int i = 0; i < arr.size(); i++) {
                JsonObject item = arr.get(i).getAsJsonObject();
                ResourceLocation taskId = new ResourceLocation(item.get("task").getAsString());
                int priority = item.get("priority").getAsInt();
                preset.setPriorityNoReorder(taskId, priority);
            }
        }

        return preset;
    }
}
