package com.github.tartaricacid.tlm_sincerely.memory;

import com.github.tartaricacid.tlm_sincerely.memory.MaidMemory.MemoryEntry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

public final class MaidMemoryManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path MEMORY_DIR = FMLPaths.CONFIGDIR.get().resolve("tlm_sincerely").resolve("maid_memories");

    private MaidMemoryManager() {
    }

    public static MaidMemory load(UUID maidUuid) {
        File file = getFile(maidUuid);
        if (!file.exists()) {
            return new MaidMemory();
        }

        try {
            String json = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            MaidMemory memory = new MaidMemory();

            if (root.has("memories")) {
                JsonObject memObj = root.getAsJsonObject("memories");
                for (Map.Entry<String, com.google.gson.JsonElement> entry : memObj.entrySet()) {
                    JsonObject item = entry.getValue().getAsJsonObject();
                    String value = item.has("value") ? item.get("value").getAsString() : "";
                    String importance = item.has("importance") ? item.get("importance").getAsString() : MemoryEntry.ARCHIVE;
                    long createdAt = item.has("createdAt") ? item.get("createdAt").getAsLong() : 0;
                    long updatedAt = item.has("updatedAt") ? item.get("updatedAt").getAsLong() : 0;

                    memory.getMemories().put(entry.getKey(),
                            new MemoryEntry(value, importance, createdAt, updatedAt));
                }
            }

            return memory;
        } catch (IOException e) {
            return new MaidMemory();
        }
    }

    public static void save(UUID maidUuid, MaidMemory memory) {
        try {
            MEMORY_DIR.toFile().mkdirs();

            JsonObject root = new JsonObject();
            JsonObject memObj = new JsonObject();

            for (Map.Entry<String, MemoryEntry> entry : memory.getMemories().entrySet()) {
                MemoryEntry mem = entry.getValue();
                JsonObject item = new JsonObject();
                item.addProperty("value", mem.value());
                item.addProperty("importance", mem.importance());
                item.addProperty("createdAt", mem.createdAt());
                item.addProperty("updatedAt", mem.updatedAt());
                memObj.add(entry.getKey(), item);
            }

            root.add("memories", memObj);

            File file = getFile(maidUuid);
            FileUtils.writeStringToFile(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public static int count() {
        File dir = MEMORY_DIR.toFile();
        if (!dir.exists()) {
            return 0;
        }
        String[] files = dir.list((d, name) -> name.endsWith(".json"));
        return files != null ? files.length : 0;
    }

    private static File getFile(UUID maidUuid) {
        return MEMORY_DIR.resolve(maidUuid.toString() + ".json").toFile();
    }
}
