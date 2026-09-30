package com.github.tartaricacid.tlm_sincerely.memory;

import com.github.tartaricacid.tlm_sincerely.memory.MaidMemory.MemoryEntry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MaidMemoryManager {
    private static final Logger LOGGER = LogManager.getLogger("TLM_Sincerely/Memory");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<UUID, MaidMemory> CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, Object> LOCKS = new ConcurrentHashMap<>();

    private MaidMemoryManager() {
    }

    private static Path memoryDir() {
        return FMLPaths.CONFIGDIR.get().resolve("tlm_sincerely").resolve("maid_memories");
    }

    public static MaidMemory load(UUID maidUuid) {
        synchronized (lockFor(maidUuid)) {
            return CACHE.computeIfAbsent(maidUuid, uuid -> loadFrom(memoryDir(), uuid)).copy();
        }
    }

    static MaidMemory loadFrom(Path dir, UUID maidUuid) {
        File file = getFile(dir, maidUuid);
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
                    long lastAccessedAt = item.has("lastAccessedAt") ? item.get("lastAccessedAt").getAsLong() : 0;
                    int accessCount = item.has("accessCount") ? item.get("accessCount").getAsInt() : 0;
                    String source = item.has("source") ? item.get("source").getAsString() : "";

                    memory.putEntry(entry.getKey(),
                            new MemoryEntry(value, importance, createdAt, updatedAt, lastAccessedAt, accessCount, source));
                }
            }

            if (root.has("meta")) {
                JsonObject metaObj = root.getAsJsonObject("meta");
                memory.setLastTidyAt(metaObj.has("lastTidyAt") ? metaObj.get("lastTidyAt").getAsLong() : 0);
            }

            return memory;
        } catch (JsonParseException | IllegalStateException | IOException e) {
            LOGGER.warn("Corrupted memory file for {}, isolating", maidUuid, e);
            File corrupted = new File(file.getParentFile(),
                    file.getName() + ".corrupted." + System.currentTimeMillis());
            try {
                Files.move(file.toPath(), corrupted.toPath());
            } catch (IOException moveEx) {
                LOGGER.error("Failed to isolate corrupted memory file {}", file.getAbsolutePath(), moveEx);
            }
            return new MaidMemory();
        }
    }

    public static boolean save(UUID maidUuid, MaidMemory memory) {
        synchronized (lockFor(maidUuid)) {
            MaidMemory candidate = memory.copy();
            boolean success = saveTo(memoryDir(), maidUuid, candidate);
            if (success) {
                CACHE.put(maidUuid, candidate);
            }
            return success;
        }
    }

    static boolean saveTo(Path dir, UUID maidUuid, MaidMemory memory) {
        dir.toFile().mkdirs();

        JsonObject root = new JsonObject();
        JsonObject memObj = new JsonObject();

        for (Map.Entry<String, MemoryEntry> entry : memory.getMemories().entrySet()) {
            MemoryEntry mem = entry.getValue();
            JsonObject item = new JsonObject();
            item.addProperty("value", mem.value());
            item.addProperty("importance", mem.importance());
            item.addProperty("createdAt", mem.createdAt());
            item.addProperty("updatedAt", mem.updatedAt());
            item.addProperty("lastAccessedAt", mem.lastAccessedAt());
            item.addProperty("accessCount", mem.accessCount());
            item.addProperty("source", mem.source());
            memObj.add(entry.getKey(), item);
        }

        root.add("memories", memObj);

        JsonObject metaObj = new JsonObject();
        metaObj.addProperty("lastTidyAt", memory.getLastTidyAt());
        root.add("meta", metaObj);

        return writeJsonAtomically(getFile(dir, maidUuid), GSON.toJson(root));
    }

    /**
     * tmp 文件 + atomic move 原子写入；目标文件系统不支持 atomic move 时退化为普通替换。
     * 并发读侧（如导出）要么读到旧完整内容、要么读到新完整内容，不会读到半个文件。
     */
    private static boolean writeJsonAtomically(File file, String json) {
        Path tmpPath = null;
        try {
            File tmpFile = new File(file.getParentFile(),
                    "." + file.getName() + "." + UUID.randomUUID() + ".tmp");
            tmpPath = tmpFile.toPath();
            FileUtils.writeStringToFile(tmpFile, json, StandardCharsets.UTF_8);

            try {
                Files.move(tmpFile.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (FileSystemException fse) {
                // Fallback for filesystems that do not support atomic move
                Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to write memory file {}", file.getAbsolutePath(), e);
            return false;
        } finally {
            if (tmpPath != null) {
                try {
                    Files.deleteIfExists(tmpPath);
                } catch (IOException cleanupError) {
                    LOGGER.debug("Failed to clean memory temp file {}", tmpPath, cleanupError);
                }
            }
        }
    }

    /** Clears only process-local cached objects; persisted player memories are never deleted here. */
    public static void clearCache() {
        CACHE.clear();
        LOCKS.clear();
        LOGGER.debug("Cleared maid memory cache");
    }

    /**
     * 复活迁移：把旧 UUID 的记忆文件拷到新 UUID。保留旧文件作备份；
     * 新 UUID 已有记忆则跳过（幂等）。调用后清掉两个 UUID 的缓存。
     *
     * @return true 表示已迁移（或无需迁移：旧文件不存在）；false 表示旧文件存在但迁移失败
     */
    public static boolean migrate(UUID oldUuid, UUID newUuid) {
        if (oldUuid.equals(newUuid)) {
            return true;
        }
        synchronized (lockFor(oldUuid)) {
            synchronized (lockFor(newUuid)) {
                Path dir = memoryDir();
                File oldFile = getFile(dir, oldUuid);
                if (!oldFile.exists()) {
                    return true;
                }
                File newFile = getFile(dir, newUuid);
                if (newFile.exists()) {
                    LOGGER.info("Skipped memory migration {} -> {}: target already exists",
                            oldUuid, newUuid);
                    CACHE.remove(newUuid);
                    return true;
                }
                try {
                    dir.toFile().mkdirs();
                    Files.copy(oldFile.toPath(), newFile.toPath());
                    LOGGER.info("Migrated maid memory {} -> {}", oldUuid, newUuid);
                    CACHE.remove(oldUuid);
                    CACHE.remove(newUuid);
                    return true;
                } catch (IOException e) {
                    LOGGER.error("Failed to migrate maid memory {} -> {}", oldUuid, newUuid, e);
                    return false;
                }
            }
        }
    }

    /**
     * MaidFileManager 迁移桥导出：返回该女仆记忆 JSON 文件原文。
     * save() 即时落盘，磁盘文件即权威快照，无需走缓存。
     *
     * @return JSON 文本；无记忆文件、文件损坏或读取失败时返回 null（导出方按“无数据”处理）
     */
    public static String exportMemoryJson(UUID maidUuid) {
        synchronized (lockFor(maidUuid)) {
            File file = getFile(memoryDir(), maidUuid);
            if (!file.exists()) {
                return null;
            }
            try {
                String json = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
                // 损坏内容不进 .maid 档案；坏文件本就应由 loadFrom 的隔离机制处理
                JsonParser.parseString(json);
                return json;
            } catch (JsonParseException | IllegalStateException | IOException e) {
                LOGGER.warn("Failed to export memory JSON for {}", maidUuid, e);
                return null;
            }
        }
    }

    /**
     * MaidFileManager 迁移桥导入：把 .maid 档案里携带的记忆 JSON 写到目标女仆（导入后
     * UUID 已由对方确定性派生，此处直接落盘到新 UUID）。目标文件已存在（同玩家重复导入
     * 同一档案）时先备份为 {@code .bak.时间戳} 再覆盖，导入的女仆是全新实体，记忆应跟随档案。
     * 写入成功后清掉该 UUID 缓存，让后续读取从新文件加载。
     *
     * @return true 表示写入成功；JSON 无效、校验失败或写盘失败返回 false
     */
    public static boolean importMemoryJson(UUID maidUuid, String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("memories")) {
                LOGGER.warn("Rejected memory JSON without 'memories' for {}", maidUuid);
                return false;
            }
        } catch (JsonParseException | IllegalStateException e) {
            LOGGER.warn("Rejected invalid memory JSON on import for {}", maidUuid, e);
            return false;
        }

        synchronized (lockFor(maidUuid)) {
            Path dir = memoryDir();
            File file = getFile(dir, maidUuid);
            if (file.exists()) {
                File backup = new File(file.getParentFile(),
                        file.getName() + ".bak." + System.currentTimeMillis());
                try {
                    Files.move(file.toPath(), backup.toPath());
                    LOGGER.info("Backed up existing memory file for {} to {}", maidUuid, backup.getName());
                } catch (IOException e) {
                    LOGGER.error("Failed to back up memory file for {}, aborting import", maidUuid, e);
                    return false;
                }
            }
            dir.toFile().mkdirs();
            boolean written = writeJsonAtomically(file, json);
            if (written) {
                CACHE.remove(maidUuid);
                LOGGER.info("Imported maid memory JSON for {}", maidUuid);
            }
            return written;
        }
    }

    private static Object lockFor(UUID maidUuid) {
        return LOCKS.computeIfAbsent(maidUuid, ignored -> new Object());
    }

    public static int count() {
        File dir = memoryDir().toFile();
        if (!dir.exists()) {
            return 0;
        }
        String[] files = dir.list((d, name) -> name.endsWith(".json"));
        return files != null ? files.length : 0;
    }

    private static File getFile(Path dir, UUID maidUuid) {
        return dir.resolve(maidUuid.toString() + ".json").toFile();
    }
}
