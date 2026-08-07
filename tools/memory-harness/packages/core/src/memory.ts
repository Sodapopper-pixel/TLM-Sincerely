export interface MemoryEntry {
  readonly value: string;
  readonly importance: "core" | "archive";
  readonly createdAt: number;
  readonly updatedAt: number;
}

export type SetResult = "added" | "updated" | "rejected-empty" | "rejected-full";

export class MaidMemory {
  private readonly memories = new Map<string, MemoryEntry>();
  private maxMemories: number;

  constructor(maxMemories = 50) {
    this.maxMemories = maxMemories;
  }

  /** Update the capacity limit at runtime (existing entries beyond the new limit are kept). */
  setMaxMemories(maxMemories: number): void {
    this.maxMemories = maxMemories;
  }

  set(key: string, value: string, importance: string): SetResult {
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
      this.memories.set(k, { value: v, importance: imp, createdAt: existing.createdAt, updatedAt: now });
      return "updated";
    }
    if (this.memories.size >= this.maxMemories) {
      return "rejected-full";
    }
    this.memories.set(k, { value: v, importance: imp, createdAt: now, updatedAt: now });
    return "added";
  }

  get(key: string): MemoryEntry | undefined {
    return this.memories.get(key);
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
    return this.memories;
  }

  putRaw(key: string, entry: MemoryEntry): void {
    this.memories.set(key, entry);
  }

  generateContextPreview(coreLimit: number, previewLength: number): string {
    if (this.memories.size === 0) {
      return "None";
    }

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
        sb += `  ${k}: ${e.value}\n`;
        coreCount++;
      } else {
        const preview = e.value.length > previewLength ? e.value.substring(0, previewLength) + "..." : e.value;
        sb += `  ${k}: "${preview}"\n`;
      }
    }

    for (const [k, e] of archive) {
      const preview = e.value.length > previewLength ? e.value.substring(0, previewLength) + "..." : e.value;
      sb += `  ${k}: "${preview}"\n`;
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
