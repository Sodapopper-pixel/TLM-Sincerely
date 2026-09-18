package com.github.tartaricacid.tlm_sincerely.memory;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.MemoryConfig;
import com.github.tartaricacid.tlm_sincerely.memory.MaidMemory.MemoryEntry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.common.ForgeConfigSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        MemoryConfig.init(builder);
        ForgeConfigSpec spec = builder.build();
        spec.setConfig(CommentedConfig.inMemory());
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
}
