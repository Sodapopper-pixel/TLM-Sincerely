import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { MaidMemory, type MemoryEntry } from "./memory.js";

export function serialize(memory: MaidMemory): string {
  const memObj: Record<string, MemoryEntry> = {};
  for (const [k, e] of memory.entries()) {
    memObj[k] = { value: e.value, importance: e.importance, createdAt: e.createdAt, updatedAt: e.updatedAt };
  }
  return JSON.stringify({ memories: memObj }, null, 2);
}

export function deserialize(json: string, maxMemories = 50): MaidMemory {
  const root = JSON.parse(json) as { memories?: Record<string, Partial<MemoryEntry>> };
  const m = new MaidMemory(maxMemories);
  const mem = root.memories ?? {};
  for (const k of Object.keys(mem)) {
    const item = mem[k];
    if (!item) {
      continue;
    }
    const importance = item.importance === "core" || item.importance === "archive" ? item.importance : "archive";
    m.putRaw(k, {
      value: item.value ?? "",
      importance,
      createdAt: item.createdAt ?? 0,
      updatedAt: item.updatedAt ?? 0,
    });
  }
  return m;
}

export function saveToDir(dir: string, uuid: string, memory: MaidMemory): void {
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, `${uuid}.json`), serialize(memory), "utf8");
}
