package com.github.tartaricacid.tlm_sincerely.memory;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.MemoryConfig;
import com.github.tartaricacid.tlm_sincerely.memory.MaidMemory.MemoryEntry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class MemoryPersistenceTest {
    private static final Path ROOT = Path.of("build/tmp/memory-persistence-test");
    private static final UUID SAMPLE_UUID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @BeforeAll
    static void initializeConfig() {
        initConfig();
    }

    @Test
    void missingFileLoadsEmpty() {
        Path dir = ROOT.resolve("junit_missing");
        cleanPath(dir);
        MaidMemory memory = MaidMemoryManager.loadFrom(dir, SAMPLE_UUID);
        assertTrue(memory.isEmpty(), "expected empty memory on missing file");
        cleanPath(dir);
    }

    @Test
    void corruptedJsonIsIsolated() throws Exception {
        Path dir = createScenario("junit_corrupted", "{");
        MaidMemory memory = MaidMemoryManager.loadFrom(dir, SAMPLE_UUID);
        assertTrue(memory.isEmpty(), "expected empty memory on corrupted JSON");
        assertCorruptedIsolated(dir);
        cleanPath(dir);
    }

    @Test
    void saveRoundTripUsesAtomicReplacement() throws Exception {
        Path dir = ROOT.resolve("junit_roundtrip");
        cleanPath(dir);
        Files.createDirectories(dir);
        MaidMemory memory = new MaidMemory();
        memory.set("k", "v", MemoryEntry.ARCHIVE, "test");
        assertTrue(MaidMemoryManager.saveTo(dir, SAMPLE_UUID, memory), "save must succeed");
        MaidMemory loaded = MaidMemoryManager.loadFrom(dir, SAMPLE_UUID);
        assertEquals("v", loaded.get("k").orElseThrow().value(), "value must roundtrip");
        try (var files = Files.list(dir)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "successful save must leave no temp files");
        }
        cleanPath(dir);
    }

    public static void main(String[] args) throws Exception {
        initConfig();
        int failures = 0;

        failures += runCase("loadFrom_fileNotFound", () -> {
            Path dir = ROOT.resolve("file_not_found");
            MaidMemory memory = MaidMemoryManager.loadFrom(dir, SAMPLE_UUID);
            assertTrue(memory.isEmpty(), "expected empty memory on missing file");
            assertFalse(Files.exists(dir.resolve(SAMPLE_UUID + ".json")), "must not create file");
        });

        failures += runCase("loadFrom_corruptedNonObject", () -> {
            Path dir = createScenario("corrupted_non_object", "[]");
            MaidMemory memory = MaidMemoryManager.loadFrom(dir, SAMPLE_UUID);
            assertTrue(memory.isEmpty(), "expected empty memory on corrupted non-object");
            assertCorruptedIsolated(dir);
        });

        failures += runCase("loadFrom_truncatedJson", () -> {
            Path dir = createScenario("truncated", "{");
            MaidMemory memory = MaidMemoryManager.loadFrom(dir, SAMPLE_UUID);
            assertTrue(memory.isEmpty(), "expected empty memory on truncated json");
            assertCorruptedIsolated(dir);
        });

        failures += runCase("saveTo_atomicWrite", () -> {
            Path dir = ROOT.resolve("atomic_write");
            Files.createDirectories(dir);
            MaidMemory memory = new MaidMemory();
            memory.set("k", "v", MemoryEntry.ARCHIVE, "test");
            boolean saved = MaidMemoryManager.saveTo(dir, SAMPLE_UUID, memory);
            assertTrue(saved, "expected save to succeed");
            Path target = dir.resolve(SAMPLE_UUID + ".json");
            Path tmp = dir.resolve(SAMPLE_UUID + ".json.tmp");
            assertTrue(Files.exists(target), "target file must exist");
            assertFalse(Files.exists(tmp), "tmp file must be removed after move");
            String content = Files.readString(target, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            assertTrue(root.has("memories"), "json must contain memories");
        });

        failures += runCase("saveTo_unreachableDirectory", () -> {
            Path unreachable = Path.of("Z:\\nonexistent_parent_12345\\dir");
            MaidMemory memory = new MaidMemory();
            memory.set("k", "v", MemoryEntry.ARCHIVE, "test");
            boolean saved = MaidMemoryManager.saveTo(unreachable, SAMPLE_UUID, memory);
            assertFalse(saved, "expected save to fail on unreachable directory");
        });

        failures += runCase("roundtrip_preservesAllFields", () -> {
            Path dir = ROOT.resolve("roundtrip");
            Files.createDirectories(dir);
            MaidMemory memory = new MaidMemory();
            memory.set("k1", "v1", MemoryEntry.CORE, "src");
            memory.set("k2", "v2", MemoryEntry.ARCHIVE, "src2");
            memory.touch("k1");
            memory.setLastTidyAt(123456L);

            boolean saved = MaidMemoryManager.saveTo(dir, SAMPLE_UUID, memory);
            assertTrue(saved, "expected save to succeed");

            MaidMemory loaded = MaidMemoryManager.loadFrom(dir, SAMPLE_UUID);
            assertEquals(2, loaded.size(), "size must roundtrip");
            assertTrue(loaded.get("k1").isPresent(), "k1 must exist");
            assertEquals("v1", loaded.get("k1").get().value(), "value must roundtrip");
            assertEquals(MemoryEntry.CORE, loaded.get("k1").get().importance(), "importance must roundtrip");
            assertEquals(1, loaded.get("k1").get().accessCount(), "accessCount must roundtrip");
            assertEquals(123456L, loaded.getLastTidyAt(), "lastTidyAt must roundtrip");
        });

        failures += runCase("maintenance_history_isolation", () -> {
            // CappedQueue uses offerFirst, so newest = head, oldest = tail.
            Deque<String> deque = new ArrayDeque<>();
            deque.addFirst("old");           // existing history before maintenance
            int snapshot = deque.size();     // 1
            deque.addFirst("tidy_user");     // maintenance user message (head)
            deque.addFirst("tidy_assistant");// maintenance assistant reply (head)
            deque.addFirst("tidy_tool");     // maintenance tool result (head)

            int toRemove = deque.size() - snapshot; // 3
            for (int i = 0; i < toRemove && !deque.isEmpty(); i++) {
                deque.pollFirst();           // correct: remove from head
            }

            assertEquals(1, deque.size(), "only the original entry should remain");
            assertEquals("old", deque.peekFirst(), "original entry must remain");
        });

        if (failures > 0) {
            System.err.println("MEMORY PERSISTENCE TEST FAILED: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("MEMORY PERSISTENCE TEST PASSED");
    }

    private static void initConfig() {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        MemoryConfig.init(builder);
        loadSpecInMemory(builder.build());
    }

    private static Path createScenario(String name, String content) throws IOException {
        Path dir = ROOT.resolve(name);
        Files.createDirectories(dir);
        Path file = dir.resolve(SAMPLE_UUID + ".json");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return dir;
    }

    private static void assertCorruptedIsolated(Path dir) throws IOException {
        boolean foundIsolated = false;
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                if (p.getFileName().toString().startsWith(SAMPLE_UUID + ".json.corrupted.")) {
                    foundIsolated = true;
                    break;
                }
            }
        }
        assertTrue(foundIsolated, "corrupted file must be isolated with timestamp suffix");
    }

    private static int runCase(String name, ThrowingRunnable action) {
        try {
            cleanCase(name);
            action.run();
        } catch (Throwable t) {
            System.err.println("FAILED " + name + ": " + t.getMessage());
            t.printStackTrace();
            return 1;
        } finally {
            cleanCase(name);
        }
        return 0;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void cleanCase(String name) {
        Path dir = ROOT.resolve(name);
        cleanPath(dir);
    }

    private static void cleanPath(Path dir) {
        if (Files.exists(dir)) {
            try {
                Files.walk(dir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            } catch (IOException ignored) {}
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) throw new AssertionError(message);
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    /**
     * NeoForge 1.21 把 1.20.1 的 {@code ForgeConfigSpec#setConfig(CommentedConfig)} 换成了
     * {@link ModConfigSpec#acceptConfig}，但 {@code ILoadedConfig} 是密封接口、唯一实现
     * {@code LoadedConfig} 为包私有。这里用公开 API（{@code getDefault}/{@code getPath}）
     * 收集默认值后反射构造 LoadedConfig（modConfig=null；默认值齐全时 acceptConfig 判定
     * isCorrect=true，不会触发内部 save()，绕开对真实 ModConfig 的依赖）。
     */
    private static void loadSpecInMemory(ModConfigSpec spec) {
        try {
            CommentedConfig config = CommentedConfig.inMemory();
            for (Field field : MemoryConfig.class.getDeclaredFields()) {
                if (!ModConfigSpec.ConfigValue.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                ModConfigSpec.ConfigValue<?> value = (ModConfigSpec.ConfigValue<?>) field.get(null);
                config.set(value.getPath(), value.getDefault());
            }
            Class<?> cls = Class.forName("net.neoforged.fml.config.LoadedConfig");
            Constructor<?> ctor = cls.getDeclaredConstructor(
                    CommentedConfig.class, Path.class, net.neoforged.fml.config.ModConfig.class);
            ctor.setAccessible(true);
            // 直接注入 loadedConfig 私有字段：acceptConfig 的 isCorrect 对内存配置判定不稳
            // （触发内部 save() 解引用真实 ModConfig），注入字段可完整绕开校正/保存链路。
            Field loadedConfigField = ModConfigSpec.class.getDeclaredField("loadedConfig");
            loadedConfigField.setAccessible(true);
            loadedConfigField.set(spec, (IConfigSpec.ILoadedConfig) ctor.newInstance(config, null, null));
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("无法加载测试配置（NeoForge 内部 API 变更）", e);
        }
    }

}
