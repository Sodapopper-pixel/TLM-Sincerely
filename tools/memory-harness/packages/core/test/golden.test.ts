import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { MaidMemory, type MemoryEntry } from "../src/memory.js";
import { serialize, deserialize } from "../src/store.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const GOLDEN = resolve(HERE, "..", "..", "..", "fixtures", "golden");

function readText(p: string): string {
  return readFileSync(p, "utf8");
}

function entry(value: string, importance: "core" | "archive"): MemoryEntry {
  return { value, importance, createdAt: 0, updatedAt: 0 };
}

describe("golden / preview", () => {
  const dir = resolve(GOLDEN, "preview");
  const inputs = readdirSync(dir).filter((f) => f.endsWith(".input.json"));
  for (const f of inputs) {
    const name = f.replace(/\.input\.json$/, "");
    it(`preview ${name}`, () => {
      const input = JSON.parse(readText(resolve(dir, f))) as {
        coreLimit: number;
        previewLength: number;
        memories: Array<{ key: string; value: string; importance: "core" | "archive" }>;
      };
      const m = new MaidMemory(input.memories.length + 10);
      for (const e of input.memories) {
        m.putRaw(e.key, entry(e.value, e.importance));
      }
      const actual = m.generateContextPreview(input.coreLimit, input.previewLength);
      const expected = readText(resolve(dir, `${name}.txt`));
      expect(actual).toBe(expected);
    });
  }
});

describe("golden / json", () => {
  const sample = readText(resolve(GOLDEN, "json", "sample.json")).trim();

  it("deserialize -> serialize roundtrip matches golden", () => {
    const m = deserialize(sample);
    expect(serialize(m).trim()).toBe(sample);
  });

  it("build from entries -> serialize matches golden", () => {
    const m = new MaidMemory(50);
    m.putRaw("name", entry("Reimu", "core"));
    m.putRaw("todo", entry("buy mushrooms", "archive"));
    expect(serialize(m).trim()).toBe(sample);
  });
});

describe("golden / set", () => {
  const dir = resolve(GOLDEN, "set");
  const files = readdirSync(dir).filter((f) => f.endsWith(".expect.json"));
  for (const f of files) {
    const name = f.replace(/\.expect\.json$/, "");
    it(`set ${name}`, () => {
      const scenario = JSON.parse(readText(resolve(dir, f))) as {
        maxMemories: number;
        seed: Array<{ key: string; value: string; importance: string }>;
        action: { key: string; value: string; importance: string };
        expected: Record<string, { value: string; importance: string }>;
      };
      const m = new MaidMemory(scenario.maxMemories);
      for (const e of scenario.seed) {
        m.set(e.key, e.value, e.importance);
      }
      m.set(scenario.action.key, scenario.action.value, scenario.action.importance);
      const actual: Record<string, { value: string; importance: string }> = {};
      for (const [k, e] of m.entries()) {
        actual[k] = { value: e.value, importance: e.importance };
      }
      const actualKeys = Object.keys(actual);
      const expectedKeys = Object.keys(scenario.expected);
      expect(actualKeys).toEqual(expectedKeys);
      expect(actual).toEqual(scenario.expected);
    });
  }
});
