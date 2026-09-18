export interface MemoryEntry {
  readonly value: string;
  readonly importance: "core" | "archive";
  readonly createdAt: number;
  readonly updatedAt: number;
  readonly lastAccessedAt: number;
  readonly accessCount: number;
  readonly source: string;
}

export type SetResult = "added" | "updated" | "rejected-empty" | "rejected-full";

export class MaidMemory {
  private readonly memories = new Map<string, MemoryEntry>();
  private maxMemories: number;
  private _lastTidyAt = 0;

  constructor(maxMemories = 50) {
    this.maxMemories = maxMemories;
  }

  setMaxMemories(maxMemories: number): void {
    this.maxMemories = maxMemories;
  }

  set(key: string, value: string, importance: string, source = ""): SetResult {
    let k = key.trim();
    let v = value.trim();
    if (k === "" || v === "") {
      return "rejected-empty";
    }
    if (k.length > 64) {
      k = k.substring(0, 64);
    }
    if (v.length > 500) {
      v = v.substring(0, 500);
    }
    const imp: "core" | "archive" = importance === "core" || importance === "archive" ? importance : "archive";
    const now = Date.now();
    const existing = this.memories.get(k);
    if (existing) {
      const finalSource = source !== "" ? source : existing.source;
      this.memories.set(k, {
        value: v, importance: imp,
        createdAt: existing.createdAt, updatedAt: now,
        lastAccessedAt: existing.lastAccessedAt, accessCount: existing.accessCount,
        source: finalSource,
      });
      return "updated";
    }
    if (this.memories.size >= this.maxMemories) {
      return "rejected-full";
    }
    this.memories.set(k, {
      value: v, importance: imp,
      createdAt: now, updatedAt: now,
      lastAccessedAt: 0, accessCount: 0,
      source,
    });
    return "added";
  }

  get(key: string): MemoryEntry | undefined {
    return this.memories.get(key);
  }

  touch(key: string): void {
    const existing = this.memories.get(key);
    if (existing) {
      this.memories.set(key, {
        value: existing.value, importance: existing.importance,
        createdAt: existing.createdAt, updatedAt: existing.updatedAt,
        lastAccessedAt: Date.now(), accessCount: existing.accessCount + 1,
        source: existing.source,
      });
    }
  }

  forget(key: string): void {
    this.memories.delete(key);
  }

  keys(): string[] {
    return [...this.memories.keys()];
  }

  size(): number {
    return this.memories.size;
  }

  isEmpty(): boolean {
    return this.memories.size === 0;
  }

  entries(): IterableIterator<[string, MemoryEntry]> {
    return this.memories.entries();
  }

  getMemories(): Map<string, MemoryEntry> {
    return new Map(this.memories);
  }

  putRaw(key: string, entry: MemoryEntry): void {
    this.memories.set(key, entry);
  }

  findOldestArchiveKey(): string | undefined {
    for (const [k, e] of this.memories) {
      if (e.importance === "archive") {
        return k;
      }
    }
    return undefined;
  }

  search(query: string): Array<[string, MemoryEntry]> {
    const lowerQuery = query.toLowerCase();
    const results: Array<[string, MemoryEntry]> = [];
    for (const entry of this.memories) {
      if (entry[0].toLowerCase().includes(lowerQuery) || entry[1].value.toLowerCase().includes(lowerQuery)) {
        results.push(entry);
        if (results.length >= 10) {
          break;
        }
      }
    }
    return results;
  }

  getLastTidyAt(): number {
    return this._lastTidyAt;
  }

  setLastTidyAt(n: number): void {
    this._lastTidyAt = n;
  }

  generateContextPreview(coreLimit: number, previewLength: number): string;
  generateContextPreview(coreLimit: number, previewLength: number, previewMode: string, showSource: boolean): string;
  generateContextPreview(coreLimit: number, previewLength: number, previewMode = "full", showSource = false): string {
    if (this.memories.size === 0) {
      return "None";
    }

    const keysOnly = previewMode === "keys_only";

    const core: Array<[string, MemoryEntry]> = [];
    const archive: Array<[string, MemoryEntry]> = [];
    for (const entry of this.memories) {
      if (entry[1].importance === "core") {
        core.push(entry);
      } else {
        archive.push(entry);
      }
    }

    let sb = "";
    let coreCount = 0;
    for (const [k, e] of core) {
      if (coreCount < coreLimit) {
        sb += `  ${k}: ${e.value}`;
        if (showSource && e.source !== "") {
          sb += ` (${e.source})`;
        }
        sb += "\n";
        coreCount++;
      } else {
        const preview = e.value.length > previewLength ? e.value.substring(0, previewLength) + "..." : e.value;
        sb += `  ${k}: "${preview}"`;
        if (showSource && e.source !== "") {
          sb += ` (${e.source})`;
        }
        sb += "\n";
      }
    }

    for (const [k, e] of archive) {
      if (keysOnly) {
        sb += `  ${k}\n`;
      } else {
        const preview = e.value.length > previewLength ? e.value.substring(0, previewLength) + "..." : e.value;
        sb += `  ${k}: "${preview}"`;
        if (showSource && e.source !== "") {
          sb += ` (${e.source})`;
        }
        sb += "\n";
      }
    }

    const coreTotal = core.length;
    const archiveTotal = archive.length;
    if (coreTotal > 0 || archiveTotal > 0) {
      sb += `${coreTotal + archiveTotal} memories total`;
      if (coreTotal > 0) {
        sb += `, ${coreTotal} core`;
      }
      if (archiveTotal > 0) {
        sb += `, ${archiveTotal} archive`;
      }
    }

    return sb;
  }
}
