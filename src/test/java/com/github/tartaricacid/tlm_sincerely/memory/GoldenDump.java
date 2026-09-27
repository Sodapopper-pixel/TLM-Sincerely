package com.github.tartaricacid.tlm_sincerely.memory;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.github.tartaricacid.tlm_sincerely.config.subconfig.MemoryConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GoldenDump {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path GOLDEN_ROOT = Path.of("tools/memory-harness/fixtures/golden");
    private static final Path PREVIEW_DIR = GOLDEN_ROOT.resolve("preview");
    private static final Path JSON_DIR = GOLDEN_ROOT.resolve("json");
    private static final Path SET_DIR = GOLDEN_ROOT.resolve("set");
    private static final UUID SAMPLE_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final boolean generate;
    private int failures = 0;

    private GoldenDump(boolean generate) {
        this.generate = generate;
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        MemoryConfig.init(b);
        loadSpecInMemory(b.build());
    }

    public static void main(String[] args) throws Exception {
        boolean gen = args.length > 0 && "--generate".equals(args[0]);
        GoldenDump d = new GoldenDump(gen);
        if (gen) {
            Files.createDirectories(PREVIEW_DIR);
            Files.createDirectories(JSON_DIR);
            Files.createDirectories(SET_DIR);
        }
        d.runPreview();
        d.runJson();
        d.runSet();
        if (d.failures > 0) {
            System.err.println("GOLDEN CHECK FAILED: " + d.failures + " mismatch(es)");
            System.exit(1);
        }
        System.out.println("GOLDEN " + (gen ? "GENERATED" : "CHECK PASSED"));
    }

    private void setMaxMemories(int n) {
        MemoryConfig.MAX_MEMORIES.set(n);
    }

    private static MaidMemory buildMemory(String[][] entries) {
        MaidMemory m = new MaidMemory();
        for (String[] e : entries) {
            m.putEntry(e[0], new MaidMemory.MemoryEntry(e[1], e[2], 0L, 0L));
        }
        return m;
    }

    private void check(String name, String expected, String actual) {
        if (!expected.equals(actual)) {
            failures++;
            System.err.println("MISMATCH " + name);
            System.err.println("--- expected ---\n" + expected);
            System.err.println("--- actual ---\n" + actual);
        }
    }

    private void runPreview() throws IOException {
        record Case(String name, String[][] entries, int coreLimit, int previewLength) {}
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("empty", new String[][]{}, 10, 30));
        cases.add(new Case("pure-core", new String[][]{
                {"name", "Reimu", "core"},
                {"owner", "Marisa", "core"}
        }, 10, 30));
        cases.add(new Case("pure-archive", new String[][]{
                {"note", "a fairly long note that exceeds the preview length of thirty", "archive"},
                {"short", "hi", "archive"}
        }, 10, 30));
        cases.add(new Case("mixed", new String[][]{
                {"name", "Reimu", "core"},
                {"todo", "buy mushrooms", "archive"}
        }, 10, 30));
        cases.add(new Case("core-exceeds-limit", new String[][]{
                {"c1", "core one", "core"},
                {"c2", "core two", "core"},
                {"c3", "core three", "core"}
        }, 1, 30));
        cases.add(new Case("core-limit-zero", new String[][]{
                {"c1", "core one", "core"},
                {"c2", "core two", "core"}
        }, 0, 30));
        cases.add(new Case("archive-exact-length", new String[][]{
                {"k", "012345678901234567890123456789", "archive"}
        }, 10, 30));
        cases.add(new Case("archive-over-length", new String[][]{
                {"k", "0123456789012345678901234567890", "archive"}
        }, 10, 30));

        for (Case c : cases) {
            MaidMemory m = buildMemory(c.entries);
            String actual = m.generateContextPreview(c.coreLimit, c.previewLength);
            Path txt = PREVIEW_DIR.resolve(c.name + ".txt");
            Path input = PREVIEW_DIR.resolve(c.name + ".input.json");
            JsonObject inJson = new JsonObject();
            inJson.addProperty("coreLimit", c.coreLimit);
            inJson.addProperty("previewLength", c.previewLength);
            JsonArray arr = new JsonArray();
            for (String[] e : c.entries) {
                JsonObject o = new JsonObject();
                o.addProperty("key", e[0]);
                o.addProperty("value", e[1]);
                o.addProperty("importance", e[2]);
                arr.add(o);
            }
            inJson.add("memories", arr);
            if (generate) {
                Files.writeString(txt, actual, StandardCharsets.UTF_8);
                Files.writeString(input, GSON.toJson(inJson), StandardCharsets.UTF_8);
            } else {
                String expected = Files.readString(txt, StandardCharsets.UTF_8);
                check("preview/" + c.name, expected, actual);
            }
        }
    }

    private void runJson() throws IOException {
        String[][] entries = {
                {"name", "Reimu", "core"},
                {"todo", "buy mushrooms", "archive"}
        };
        MaidMemory m = buildMemory(entries);
        Path golden = JSON_DIR.resolve("sample.json");

        Path tmp = Files.createTempDirectory("golden-json-");
        MaidMemoryManager.saveTo(tmp, SAMPLE_UUID, m);
        Path written = tmp.resolve(SAMPLE_UUID.toString() + ".json");
        String actual = Files.readString(written, StandardCharsets.UTF_8).trim();

        if (generate) {
            Files.writeString(golden, actual + "\n", StandardCharsets.UTF_8);
        } else {
            String expected = Files.readString(golden, StandardCharsets.UTF_8).trim();
            check("json/sample", expected, actual);
        }

        MaidMemory loaded = MaidMemoryManager.loadFrom(tmp, SAMPLE_UUID);
        MaidMemoryManager.saveTo(tmp, SAMPLE_UUID, loaded);
        String actual2 = Files.readString(written, StandardCharsets.UTF_8).trim();
        check("json/sample-roundtrip", actual, actual2);
    }

    private void runSet() throws IOException {
        setCase("trim", 50, new String[][]{}, " foo ", " bar ", "core",
                new String[][]{{"foo", "bar", "core"}});
        setCase("key-cap", 50, new String[][]{}, "k".repeat(65), "v", "archive",
                new String[][]{{"k".repeat(64), "v", "archive"}});
        setCase("value-cap", 50, new String[][]{}, "k", "v".repeat(501), "archive",
                new String[][]{{"k", "v".repeat(500), "archive"}});
        setCase("importance-invalid", 50, new String[][]{}, "k", "v", "weird",
                new String[][]{{"k", "v", "archive"}});
        setCase("capacity-update-existing", 2,
                new String[][]{{"a", "1", "archive"}, {"b", "2", "archive"}},
                "a", "new", "core",
                new String[][]{{"a", "new", "core"}, {"b", "2", "archive"}});
        setCase("capacity-reject-new", 2,
                new String[][]{{"a", "1", "archive"}, {"b", "2", "archive"}},
                "c", "v", "archive",
                new String[][]{{"a", "1", "archive"}, {"b", "2", "archive"}});
    }

    private void setCase(String name, int maxMemories, String[][] seed,
                         String aKey, String aValue, String aImportance, String[][] expected) throws IOException {
        setMaxMemories(maxMemories);
        MaidMemory m = new MaidMemory();
        for (String[] e : seed) {
            m.set(e[0], e[1], e[2]);
        }
        m.set(aKey, aValue, aImportance);
        JsonObject expectedObj = normalize(m);
        Path p = SET_DIR.resolve(name + ".expect.json");
        if (generate) {
            JsonObject scenario = new JsonObject();
            scenario.addProperty("maxMemories", maxMemories);
            JsonArray seedArr = new JsonArray();
            for (String[] e : seed) {
                JsonObject o = new JsonObject();
                o.addProperty("key", e[0]);
                o.addProperty("value", e[1]);
                o.addProperty("importance", e[2]);
                seedArr.add(o);
            }
            JsonObject action = new JsonObject();
            action.addProperty("key", aKey);
            action.addProperty("value", aValue);
            action.addProperty("importance", aImportance);
            scenario.add("seed", seedArr);
            scenario.add("action", action);
            scenario.add("expected", expectedObj);
            Files.writeString(p, GSON.toJson(scenario), StandardCharsets.UTF_8);
        } else {
            JsonObject fileObj = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            String exp = GSON.toJson(fileObj.get("expected")).trim();
            String act = GSON.toJson(expectedObj).trim();
            check("set/" + name, exp, act);
        }
    }

    private static JsonObject normalize(MaidMemory m) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, MaidMemory.MemoryEntry> e : m.getMemories().entrySet()) {
            JsonObject item = new JsonObject();
            item.addProperty("value", e.getValue().value());
            item.addProperty("importance", e.getValue().importance());
            o.add(e.getKey(), item);
        }
        return o;
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
