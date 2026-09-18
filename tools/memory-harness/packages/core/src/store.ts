import { mkdir, rename, rm, writeFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { join } from "node:path";
import { MaidMemory, type MemoryEntry } from "./memory.js";

export function serialize(memory: MaidMemory): string {
  const memObj: Record<string, MemoryEntry> = {};
  for (const [k, e] of memory.entries()) {
    memObj[k] = {
      value: e.value,
      importance: e.importance,
      createdAt: e.createdAt,
      updatedAt: e.updatedAt,
      lastAccessedAt: e.lastAccessedAt,
      accessCount: e.accessCount,
      source: e.source,
    };
  }
  return JSON.stringify({
    memories: memObj,
    meta: { lastTidyAt: memory.getLastTidyAt() },
  }, null, 2);
}

export function deserialize(json: string, maxMemories = 50): MaidMemory {
  const m = new MaidMemory(maxMemories);

  let root: {
    memories?: Record<string, Partial<MemoryEntry>>;
    meta?: { lastTidyAt?: number };
  };

  try {
    root = JSON.parse(json) as typeof root;
  } catch (e) {
    console.error(
      `[core] deserialize: corrupted JSON, returning empty memory. Error: ${(e as Error).message}`,
    );
    return m;
  }

  if (root === null || typeof root !== "object" || Array.isArray(root)) {
    console.error(
      `[core] deserialize: unexpected root type (${typeof root}), returning empty memory.`,
    );
    return m;
  }

  const mem = root.memories ?? {};
  if (typeof mem !== "object" || Array.isArray(mem)) {
    console.error(
      `[core] deserialize: memories field is not an object, returning empty memory.`,
    );
    return m;
  }

  for (const k of Object.keys(mem)) {
    const item = mem[k];
    if (!item || typeof item !== "object") {
      continue;
    }
    const importance = item.importance === "core" || item.importance === "archive" ? item.importance : "archive";
    m.putRaw(k, {
      value: typeof item.value === "string" ? item.value : "",
      importance,
      createdAt: typeof item.createdAt === "number" ? item.createdAt : 0,
      updatedAt: typeof item.updatedAt === "number" ? item.updatedAt : 0,
      lastAccessedAt: typeof item.lastAccessedAt === "number" ? item.lastAccessedAt : 0,
      accessCount: typeof item.accessCount === "number" ? item.accessCount : 0,
      source: typeof item.source === "string" ? item.source : "",
    });
  }
  if (root.meta && typeof root.meta === "object" && !Array.isArray(root.meta)) {
    m.setLastTidyAt(typeof root.meta.lastTidyAt === "number" ? root.meta.lastTidyAt : 0);
  }
  return m;
}

/**
 * Atomically save memory to disk.
 * Writes to a temp file first, then renames to the final path.
 * Uses async fs/promises to avoid blocking the event loop.
 */
export async function saveToDir(dir: string, uuid: string, memory: MaidMemory): Promise<void> {
  await mkdir(dir, { recursive: true });
  const finalPath = join(dir, `${uuid}.json`);
  const tmpPath = join(dir, `.${uuid}.${randomUUID()}.tmp`);
  const content = serialize(memory);
  await writeFile(tmpPath, content, "utf8");
  try {
    // Node's Windows implementation uses MOVEFILE_REPLACE_EXISTING, so this
    // atomically replaces an existing file without a delete-before-rename gap.
    // Antivirus/indexers can transiently lock the target, so retry the same
    // same-directory replacement while retaining the prior complete file.
    await renameWithRetry(tmpPath, finalPath);
  } catch (e) {
    await rm(tmpPath, { force: true }).catch(() => undefined);
    throw e;
  }
}

async function renameWithRetry(source: string, target: string): Promise<void> {
  const retryDelaysMs = [0, 20, 60, 120];
  let lastError: unknown;
  for (const delay of retryDelaysMs) {
    if (delay > 0) {
      await new Promise<void>((resolveDelay) => setTimeout(resolveDelay, delay));
    }
    try {
      await rename(source, target);
      return;
    } catch (e) {
      lastError = e;
      const code = (e as NodeJS.ErrnoException).code;
      if (code !== "EPERM" && code !== "EACCES" && code !== "EBUSY") {
        throw e;
      }
    }
  }
  throw lastError;
}
