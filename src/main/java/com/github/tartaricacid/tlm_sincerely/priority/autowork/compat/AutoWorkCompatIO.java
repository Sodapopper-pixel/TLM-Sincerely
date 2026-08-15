package com.github.tartaricacid.tlm_sincerely.priority.autowork.compat;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPresetIO;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.LinkedHashSet;
import java.util.Set;

/** Atomic JSON persistence for the hand-editable auto work compatibility policy. */
final class AutoWorkCompatIO {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoWorkCompatIO.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "auto_work_compat.json";
    private static final int VERSION = 1;

    private AutoWorkCompatIO() {
    }

    static AutoWorkCompatConfig loadOrCreate() {
        Path target = AutoWorkPresetIO.getConfigDir().resolve(FILE_NAME);
        if (!Files.exists(target)) {
            AutoWorkCompatConfig defaults = AutoWorkCompatConfig.defaults();
            save(defaults);
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            AutoWorkCompatService.ReminderLevel reminderLevel = parseReminderLevel(root);
            return new AutoWorkCompatConfig(readUidSet(root, "blacklist"), readUidSet(root, "whitelist"),
                    readUidSet(root, "knownBadFallback"), reminderLevel);
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("[TaskCompat] failed to read {}; retaining the file and using safe defaults", target, exception);
            return AutoWorkCompatConfig.defaults();
        }
    }

    static void save(AutoWorkCompatConfig config) {
        Path configDir = AutoWorkPresetIO.getConfigDir();
        Path target = configDir.resolve(FILE_NAME);
        Path temp = target.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(configDir);
            JsonObject root = new JsonObject();
            root.addProperty("version", VERSION);
            root.add("blacklist", writeUidSet(config.blacklist()));
            root.add("whitelist", writeUidSet(config.whitelist()));
            root.add("knownBadFallback", writeUidSet(config.knownBadFallback()));
            root.addProperty("reminderLevel", config.reminderLevel().name());
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                LOGGER.warn("[TaskCompat] ATOMIC_MOVE unsupported for {}; using replace", target);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            LOGGER.error("[TaskCompat] failed to save {}", target, exception);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
        }
    }

    private static Set<String> readUidSet(JsonObject root, String name) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (!root.has(name) || !root.get(name).isJsonArray()) {
            return result;
        }
        for (JsonElement element : root.getAsJsonArray(name)) {
            if (element.isJsonPrimitive()) {
                String uid = element.getAsString().trim();
                if (!uid.isEmpty()) {
                    result.add(uid);
                }
            }
        }
        return result;
    }

    private static JsonArray writeUidSet(Set<String> values) {
        JsonArray result = new JsonArray();
        for (String value : values) {
            result.add(value);
        }
        return result;
    }

    private static AutoWorkCompatService.ReminderLevel parseReminderLevel(JsonObject root) {
        if (!root.has("reminderLevel") || !root.get("reminderLevel").isJsonPrimitive()) {
            return AutoWorkCompatService.ReminderLevel.OP_ONLY;
        }
        try {
            return AutoWorkCompatService.ReminderLevel.valueOf(root.get("reminderLevel").getAsString().trim());
        } catch (IllegalArgumentException exception) {
            return AutoWorkCompatService.ReminderLevel.OP_ONLY;
        }
    }
}
