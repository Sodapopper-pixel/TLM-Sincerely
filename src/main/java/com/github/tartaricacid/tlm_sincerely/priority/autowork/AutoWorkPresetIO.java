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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * File IO for the auto work preset library (T-2 A2).
 *
 * <p>Owns the new v3 file {@code auto_work_presets.json}. Performs v1/v2 →
 * v3 migration from the legacy {@code task_priority_presets.json} on first
 * load: the legacy file is renamed to a timestamped {@code .bak} backup
 * before any data is moved, and the v3 file is written via a temp file +
 * atomic move.
 *
 * <p>All file mutations are performed by the server thread. The class is
 * stateless; callers pass the working directory and the in-memory map.
 */
public final class AutoWorkPresetIO {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoWorkPresetIO.class);

    /** v3 file written by this service. */
    public static final String V3_FILE_NAME = "auto_work_presets.json";
    /** Legacy file migrated on first load (v1 or v2). */
    public static final String LEGACY_FILE_NAME = "task_priority_presets.json";
    public static final int CURRENT_VERSION = 3;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private AutoWorkPresetIO() {
    }

    public static Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get().resolve("tlm_sincerely");
    }

    /** Loads the preset library; creates a default if no file exists. */
    public static LoadedLibrary loadOrCreate() {
        Path configDir = getConfigDir();
        Path v3File = configDir.resolve(V3_FILE_NAME);
        Path legacyFile = configDir.resolve(LEGACY_FILE_NAME);

        if (Files.exists(v3File)) {
            return loadV3FromFile(v3File);
        }
        if (Files.exists(legacyFile)) {
            LoadedLibrary migrated = migrateLegacy(configDir, legacyFile);
            if (migrated != null) {
                return migrated;
            }
            // Migration failed; preserve the legacy file and fall back to a
            // default library so the server can still start.
            isolateBrokenFile(legacyFile, "pre_migration_failure");
            return LoadedLibrary.emptyWithDefault();
        }
        return LoadedLibrary.emptyWithDefault();
    }

    /** Atomic write: temp file + {@code Files.move(ATOMIC_MOVE, REPLACE_EXISTING)}. */
    public static void save(LoadedLibrary library) {
        Path configDir = getConfigDir();
        Path target = configDir.resolve(V3_FILE_NAME);
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
            // best effort: clean up temp file
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
        }
    }

    // ---------------- v3 load ----------------

    private static LoadedLibrary loadV3FromFile(Path v3File) {
        try (Reader reader = Files.newBufferedReader(v3File, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            int version = root.has("version") ? root.get("version").getAsInt() : 0;
            if (version != CURRENT_VERSION) {
                LOGGER.warn("[AutoWorkPresetIO] unknown preset file version {} in {}, isolating",
                        version, v3File);
                isolateBrokenFile(v3File, "unknown_version");
                return LoadedLibrary.emptyWithDefault();
            }

            Map<UUID, AutoWorkPreset> presets = new LinkedHashMap<>();
            if (root.has("presets") && root.get("presets").isJsonArray()) {
                JsonArray arr = root.getAsJsonArray("presets");
                Set<UUID> seenIds = new HashSet<>();
                for (int i = 0; i < arr.size(); i++) {
                    JsonElement el = arr.get(i);
                    if (!el.isJsonObject()) continue;
                    AutoWorkPreset preset = deserializeV3Preset(el.getAsJsonObject());
                    if (preset == null) continue;
                    if (!seenIds.add(preset.getId())) {
                        LOGGER.warn("[AutoWorkPresetIO] duplicate preset id {} in {}, skipping",
                                preset.getId(), v3File);
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
            LOGGER.error("[AutoWorkPresetIO] failed to read v3 preset file {}", v3File, e);
            isolateBrokenFile(v3File, "parse_failure");
            return LoadedLibrary.emptyWithDefault();
        }
    }

    private static AutoWorkPreset deserializeV3Preset(JsonObject obj) {
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

    // ---------------- v1 / v2 migration ----------------

    private static LoadedLibrary migrateLegacy(Path configDir, Path legacyFile) {
        LOGGER.info("[AutoWorkPresetIO] migrating legacy preset file {}", legacyFile);
        try (Reader reader = Files.newBufferedReader(legacyFile, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            Map<UUID, AutoWorkPreset> migrated = new LinkedHashMap<>();
            UUID defaultId = null;

            if (root.has("presets") && root.get("presets").isJsonArray()) {
                JsonArray arr = root.getAsJsonArray("presets");
                for (int i = 0; i < arr.size(); i++) {
                    JsonElement el = arr.get(i);
                    if (!el.isJsonObject()) continue;
                    AutoWorkPreset preset = migrateLegacyPreset(el.getAsJsonObject());
                    if (preset == null) continue;
                    if (migrated.containsKey(preset.getId())) {
                        // legacy file has no UUID; regenerate on collision
                        preset = new AutoWorkPreset(UUID.randomUUID(), preset.getName(), preset.getOrder());
                    }
                    migrated.put(preset.getId(), preset);
                }
            }

            // Map old "active" name to defaultPreset UUID.
            if (root.has("active") && root.get("active").isJsonPrimitive()) {
                String activeName = root.get("active").getAsString();
                for (AutoWorkPreset preset : migrated.values()) {
                    if (preset.getName().equals(activeName)) {
                        defaultId = preset.getId();
                        break;
                    }
                }
            }
            if (defaultId == null) {
                defaultId = migrated.isEmpty() ? null : migrated.keySet().iterator().next();
            }
            if (migrated.isEmpty()) {
                AutoWorkPreset def = LoadedLibrary.makeDefault();
                migrated.put(def.getId(), def);
                defaultId = def.getId();
            }

            // Back up the legacy file before any v3 write.
            backupLegacyFile(legacyFile, readVersionOrUnversioned(root));

            LoadedLibrary result = new LoadedLibrary(migrated, defaultId);
            // Persist v3 immediately so the migration is committed before any
            // server tick that might re-enter the legacy path.
            save(result);
            return result;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[AutoWorkPresetIO] failed to migrate legacy preset file", e);
            return null;
        }
    }

    private static int readVersionOrUnversioned(JsonObject root) {
        if (root.has("version") && root.get("version").isJsonPrimitive()) {
            try {
                return root.get("version").getAsInt();
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        return 1;
    }

    /**
     * v1: numeric priority ascending (lower = higher priority), same priority
     * keeps the order list's relative order; tasks only in priorities are
     * preserved, tasks only in order are appended. v2: order list is the
     * authoritative ordering; numeric priorities are dropped.
     */
    private static AutoWorkPreset migrateLegacyPreset(JsonObject obj) {
        if (!obj.has("name")) {
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
                try {
                    ResourceLocation rid = new ResourceLocation(arr.get(i).getAsString());
                    if (!order.contains(rid)) {
                        order.add(rid);
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        boolean hasPriorities = obj.has("priorities") && obj.get("priorities").isJsonArray();
        List<ResourceLocation> finalOrder;
        if (hasPriorities) {
            // v1: build merged order from priorities (ascending), then keep
            // the legacy order list's relative order within the same priority
            // bucket, then append any tasks present only in order.
            JsonArray priArr = obj.getAsJsonArray("priorities");
            List<PriorityEntry> entries = new ArrayList<>();
            for (int i = 0; i < priArr.size(); i++) {
                JsonElement el = priArr.get(i);
                if (!el.isJsonObject()) continue;
                JsonObject item = el.getAsJsonObject();
                if (!item.has("task") || !item.has("priority")) continue;
                try {
                    ResourceLocation rid = new ResourceLocation(item.get("task").getAsString());
                    int p = item.get("priority").getAsInt();
                    entries.add(new PriorityEntry(rid, p));
                } catch (RuntimeException ignored) {
                }
            }
            finalOrder = mergeOrder(entries, order);
        } else {
            finalOrder = order;
        }
        if (finalOrder.isEmpty()) {
            return null;
        }
        return new AutoWorkPreset(UUID.randomUUID(), name, finalOrder);
    }

    /**
     * Pure helper: combines {@code entries} (priority-ascending) with the
     * legacy order list. Sorts entries so that same-priority tasks keep
     * the legacy order list's relative position; preserves any task that
     * was only present in the order list.
     */
    private static List<ResourceLocation> mergeOrder(List<PriorityEntry> entries,
                                                     List<ResourceLocation> legacyOrder) {
        final List<ResourceLocation> orderSnapshot = new ArrayList<>(legacyOrder);
        entries.sort(new PriorityEntryComparator(orderSnapshot));
        List<ResourceLocation> merged = new ArrayList<>();
        for (PriorityEntry entry : entries) {
            if (!merged.contains(entry.task)) {
                merged.add(entry.task);
            }
        }
        for (ResourceLocation rid : orderSnapshot) {
            if (!merged.contains(rid)) {
                merged.add(rid);
            }
        }
        return merged;
    }

    private static final class PriorityEntryComparator
            implements java.util.Comparator<PriorityEntry> {
        private final List<ResourceLocation> orderSnapshot;

        private PriorityEntryComparator(List<ResourceLocation> orderSnapshot) {
            this.orderSnapshot = orderSnapshot;
        }

        @Override
        public int compare(PriorityEntry a, PriorityEntry b) {
            int cmp = Integer.compare(a.priority, b.priority);
            if (cmp != 0) return cmp;
            int ia = orderSnapshot.indexOf(a.task);
            int ib = orderSnapshot.indexOf(b.task);
            if (ia == -1 && ib == -1) return 0;
            if (ia == -1) return 1;
            if (ib == -1) return -1;
            return Integer.compare(ia, ib);
        }
    }

    private static void backupLegacyFile(Path legacyFile, int oldVersion) {
        String ts = LocalDateTime.now().format(TS_FORMAT);
        Path backup = legacyFile.resolveSibling(
                LEGACY_FILE_NAME + ".v" + oldVersion + ".bak." + ts);
        try {
            Files.move(legacyFile, backup, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info("[AutoWorkPresetIO] backed up legacy file to {}", backup);
        } catch (AtomicMoveNotSupportedException unsupported) {
            try {
                Files.move(legacyFile, backup, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("[AutoWorkPresetIO] backed up legacy file (non-atomic) to {}", backup);
            } catch (IOException e) {
                LOGGER.error("[AutoWorkPresetIO] failed to back up legacy file", e);
            }
        } catch (IOException e) {
            LOGGER.error("[AutoWorkPresetIO] failed to back up legacy file", e);
        }
    }

    private static void isolateBrokenFile(Path file, String reason) {
        String ts = LocalDateTime.now().format(TS_FORMAT);
        Path broken = file.resolveSibling(
                file.getFileName() + ".broken." + reason + "." + ts);
        try {
            Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.warn("[AutoWorkPresetIO] isolated broken file {} to {}", file, broken);
        } catch (IOException e) {
            LOGGER.error("[AutoWorkPresetIO] failed to isolate broken file {}", file, e);
        }
    }

    private record PriorityEntry(ResourceLocation task, int priority) {
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
