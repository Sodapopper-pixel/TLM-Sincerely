package com.github.tartaricacid.tlm_sincerely.priority.autowork;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * v3 JSON IO for the auto work preset library.
 *
 * <p>The server reads this file once per {@code MinecraftServer} as a frozen
 * seed; it never writes it. The client owns the file as its private library
 * (see {@link com.github.tartaricacid.tlm_sincerely.client.autowork.AutoWorkClientLibrary})
 * and is the only writer. Legacy v1/v2 files are not migrated: a missing (or
 * non-v3) file simply yields the empty library with the built-in default.
 */
public final class AutoWorkPresetIO {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoWorkPresetIO.class);

    public static final String V3_FILE_NAME = "auto_work_presets.json";
    public static final int CURRENT_VERSION = 3;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AutoWorkPresetIO() {
    }

    public static Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get().resolve("tlm_sincerely");
    }

    public static Path getLibraryFile() {
        return getConfigDir().resolve(V3_FILE_NAME);
    }

    /** Reads the v3 library; missing or unreadable files yield the default library. */
    public static LoadedLibrary load() {
        Path file = getLibraryFile();
        if (!Files.exists(file)) {
            return LoadedLibrary.emptyWithDefault();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            int version = root.has("version") ? root.get("version").getAsInt() : 0;
            if (version != CURRENT_VERSION) {
                LOGGER.warn("[AutoWorkPresetIO] ignoring preset file version {} in {} (only v3 is read)",
                        version, file);
                return LoadedLibrary.emptyWithDefault();
            }

            Map<UUID, AutoWorkPreset> presets = new LinkedHashMap<>();
            if (root.has("presets") && root.get("presets").isJsonArray()) {
                JsonArray arr = root.getAsJsonArray("presets");
                Set<UUID> seenIds = new HashSet<>();
                for (int i = 0; i < arr.size(); i++) {
                    JsonElement el = arr.get(i);
                    if (!el.isJsonObject()) continue;
                    AutoWorkPreset preset = deserializePreset(el.getAsJsonObject());
                    if (preset == null) continue;
                    if (!seenIds.add(preset.getId())) {
                        LOGGER.warn("[AutoWorkPresetIO] duplicate preset id {} in {}, skipping",
                                preset.getId(), file);
                        continue;
                    }
                    presets.put(preset.getId(), preset);
                }
            }

            UUID defaultId = null;
            if (root.has("defaultPreset") && root.get("defaultPreset").isJsonPrimitive()) {
                String raw = root.get("defaultPreset").getAsString();
                try {
                    defaultId = UUID.fromString(raw);
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (defaultId == null || !presets.containsKey(defaultId)) {
                defaultId = presets.isEmpty() ? null : presets.keySet().iterator().next();
            }
            if (presets.isEmpty()) {
                AutoWorkPreset def = LoadedLibrary.makeDefault();
                presets.put(def.getId(), def);
                defaultId = def.getId();
            }
            return new LoadedLibrary(presets, defaultId);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[AutoWorkPresetIO] failed to read preset file {}", file, e);
            return LoadedLibrary.emptyWithDefault();
        }
    }

    /** Atomic write: temp file + {@code Files.move(ATOMIC_MOVE, REPLACE_EXISTING)}. */
    public static void save(LoadedLibrary library) {
        Path configDir = getConfigDir();
        Path target = getLibraryFile();
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");

        try {
            Files.createDirectories(configDir);
            JsonObject root = new JsonObject();
            root.addProperty("version", CURRENT_VERSION);
            root.addProperty("defaultPreset", library.defaultPresetId().toString());
            JsonArray arr = new JsonArray();
            for (AutoWorkPreset preset : library.presetsInOrder()) {
                arr.add(serializePreset(preset));
            }
            root.add("presets", arr);

            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }

            try {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                LOGGER.warn("[AutoWorkPresetIO] ATOMIC_MOVE unsupported, falling back to non-atomic replace");
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("[AutoWorkPresetIO] failed to save presets to {}", target, e);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
        }
    }

    private static AutoWorkPreset deserializePreset(JsonObject obj) {
        if (!obj.has("id") || !obj.has("name")) {
            return null;
        }
        UUID id;
        try {
            id = UUID.fromString(obj.get("id").getAsString());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String name = obj.get("name").getAsString();
        if (name.isEmpty()) {
            return null;
        }
        List<ResourceLocation> order = new ArrayList<>();
        if (obj.has("order") && obj.get("order").isJsonArray()) {
            JsonArray arr = obj.getAsJsonArray("order");
            for (int i = 0; i < arr.size(); i++) {
                String raw = arr.get(i).getAsString();
                try {
                    ResourceLocation rid = new ResourceLocation(raw);
                    if (!order.contains(rid)) {
                        order.add(rid);
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        return new AutoWorkPreset(id, name, order);
    }

    private static JsonObject serializePreset(AutoWorkPreset preset) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", preset.getId().toString());
        obj.addProperty("name", preset.getName());
        JsonArray order = new JsonArray();
        for (ResourceLocation id : preset.getOrder()) {
            order.add(id.toString());
        }
        obj.add("order", order);
        return obj;
    }

    /** Result of a load: ordered presets plus the default preset id. */
    public static final class LoadedLibrary {
        private final Map<UUID, AutoWorkPreset> presets;
        private final UUID defaultPresetId;

        public LoadedLibrary(Map<UUID, AutoWorkPreset> presets, UUID defaultPresetId) {
            this.presets = presets;
            this.defaultPresetId = defaultPresetId;
        }

        public static LoadedLibrary emptyWithDefault() {
            AutoWorkPreset def = makeDefault();
            Map<UUID, AutoWorkPreset> map = new LinkedHashMap<>();
            map.put(def.getId(), def);
            return new LoadedLibrary(map, def.getId());
        }

        public static AutoWorkPreset makeDefault() {
            return new AutoWorkPreset(
                    new UUID(0x9f5a2c3e0000L, 0x0000_0000_0000_0001L),
                    "默认预设",
                    new ArrayList<>());
        }

        public Map<UUID, AutoWorkPreset> presets() {
            return presets;
        }

        public List<AutoWorkPreset> presetsInOrder() {
            return new ArrayList<>(presets.values());
        }

        public UUID defaultPresetId() {
            return defaultPresetId;
        }
    }
}
