import type { MaidMemory, MemoryEntry } from "@tlm-harness/core";
import type { ToolSchema } from "../types.js";
import { type MemoryDiff, type Tool, type ToolContext, type ToolResult, invalidParam } from "../tool-registry.js";

const TOOL_ID = "tlm_memory";
const TOOL_DESC = `Manage persistent key-value memories for the maid.
Use 'remember' to store important facts about the player (preferences, history, promises).
Use 'recall' to retrieve the full content of a specific memory.
Use 'forget' to delete a memory when the player asks or when it's no longer relevant.
Use 'search' to find memories by keyword (matches key or value, returns up to 10 results).
Use 'merge' to combine 2-5 similar archive entries into one.`;

const MAINTENANCE_ACTIONS = new Set(["search", "recall", "merge"]);
const MAINTENANCE_MSG = "Only search/recall/merge are allowed during memory maintenance.";

function snapshot(memory: MaidMemory): Map<string, MemoryEntry> {
  return new Map(memory.getMemories());
}

function computeDiff(before: Map<string, MemoryEntry>, after: Map<string, MemoryEntry>): MemoryDiff {
  const added: string[] = [];
  const updated: string[] = [];
  const removed: string[] = [];
  for (const [k, v] of after) {
    const prev = before.get(k);
    if (!prev) {
      added.push(k);
    } else if (prev.value !== v.value || prev.importance !== v.importance) {
      updated.push(k);
    }
  }
  for (const k of before.keys()) {
    if (!after.has(k)) {
      removed.push(k);
    }
  }
  return { added, updated, removed };
}

function getSource(ctx: ToolContext): string {
  return ctx.maid.userName ?? "";
}

export class MemoryTool implements Tool {
  readonly id = TOOL_ID;

  schema(): ToolSchema {
    return {
      type: "function",
      function: {
        name: TOOL_ID,
        description: TOOL_DESC,
        parameters: {
          type: "object",
          properties: {
            action: {
              type: "string",
              enum: ["remember", "recall", "forget", "search", "merge"],
              description: "The action to perform on memories",
            },
            key: {
              type: "string",
              description:
                "The memory key (unique identifier for the memory). For 'remember' this may be a new key; for 'recall'/'forget' use an existing key (see preview or 'Available keys' in tool errors). For 'merge' this is the target key.",
            },
            value: { type: "string", description: "The memory value. Required for 'remember' and 'merge' actions." },
            importance: {
              type: "string",
              enum: ["core", "archive"],
              description: "Memory importance: 'core' (always shown in full) or 'archive' (shown as preview)",
            },
            query: {
              type: "string",
              description: "Search query for 'search' action. Returns matching memory keys and previews (up to 10).",
            },
            keys: {
              type: "array",
              items: { type: "string" },
              minItems: 2,
              maxItems: 5,
              uniqueItems: true,
              description: "Source keys to merge (2-5 existing archive entries) for 'merge' action.",
            },
          },
          required: ["action"],
        },
      },
    };
  }

  execute(args: Record<string, unknown>, ctx: ToolContext): ToolResult {
    if (!ctx.config.enabled) {
      return { content: "Memory system is currently disabled." };
    }
    const action = String(args.action ?? "");
    const key = String(args.key ?? "");
    const value = String(args.value ?? "");
    const importance = String(args.importance ?? "archive");
    const query = String(args.query ?? "");
    const keys = Array.isArray(args.keys) ? args.keys.map((k) => String(k)) : [];

    if (ctx.maintaining && !MAINTENANCE_ACTIONS.has(action)) {
      return { content: MAINTENANCE_MSG };
    }

    switch (action) {
      case "remember":
        return this.remember(ctx, key, value, importance);
      case "recall":
        return this.recall(ctx, key);
      case "forget":
        return this.forget(ctx, key);
      case "search":
        return this.search(ctx, query);
      case "merge":
        return this.merge(ctx, key, value, keys);
      default:
        return { content: invalidParam("action", ["remember", "recall", "forget", "search", "merge"], `Unknown action: ${action}`) };
    }
  }

  private remember(ctx: ToolContext, key: string, value: string, importance: string): ToolResult {
    if (!key) {
      return { content: invalidParam("key", ["<memory key>"], "key is required for 'remember' action") };
    }
    if (!value) {
      return { content: invalidParam("value", ["<memory value>"], "value is required for 'remember' action") };
    }

    const max = ctx.config.maxMemories;
    const trimmedKey = key.trim();
    const before = snapshot(ctx.memory);

    if (ctx.memory.size() >= max && !ctx.memory.getMemories().has(trimmedKey)) {
      if (!ctx.config.autoEvict) {
        return { content: `Memory limit reached (${max} max). Delete some memories first with 'forget'.` };
      }
      const oldestArchive = ctx.memory.findOldestArchiveKey();
      if (oldestArchive === undefined) {
        return {
          content: `Memory limit reached (${max} max) and all entries are core. Ask the player which memory to forget.`,
        };
      }
      ctx.memory.forget(oldestArchive);
      ctx.memory.set(key, value, importance, getSource(ctx));
      ctx.save();
      return {
        content: `Remembered '${key}' = "${value}" (${importance}). Evicted oldest archive '${oldestArchive}' to make room.`,
        memoryDiff: computeDiff(before, snapshot(ctx.memory)),
      };
    }

    ctx.memory.set(key, value, importance, getSource(ctx));
    ctx.save();
    return {
      content: `Remembered '${key}' = "${value}" (${importance})`,
      memoryDiff: computeDiff(before, snapshot(ctx.memory)),
    };
  }

  private recall(ctx: ToolContext, key: string): ToolResult {
    if (!key) {
      const ks = ctx.memory.keys();
      return {
        content: invalidParam("key", ks.length === 0 ? ["no memories stored"] : ks, "key is required for 'recall' action"),
      };
    }
    const entry = ctx.memory.get(key);
    if (!entry) {
      return { content: `Memory key '${key}' not found. Available keys: [${ctx.memory.keys().join(", ")}]` };
    }
    ctx.memory.touch(key);
    ctx.save();
    let output = `[${entry.importance}] ${key}: ${entry.value}`;
    if (ctx.config.showSource && entry.source !== "") {
      output += ` (source: ${entry.source})`;
    }
    return { content: output };
  }

  private forget(ctx: ToolContext, key: string): ToolResult {
    if (!key) {
      const ks = ctx.memory.keys();
      return {
        content: invalidParam("key", ks.length === 0 ? ["no memories stored"] : ks, "key is required for 'forget' action"),
      };
    }
    if (!ctx.memory.getMemories().has(key)) {
      return { content: `Memory key '${key}' not found. Available keys: [${ctx.memory.keys().join(", ")}]` };
    }
    const before = snapshot(ctx.memory);
    ctx.memory.forget(key);
    ctx.save();
    return { content: `Forgot memory '${key}'`, memoryDiff: computeDiff(before, snapshot(ctx.memory)) };
  }

  private search(ctx: ToolContext, query: string): ToolResult {
    const q = query.trim();
    if (!q) {
      return { content: invalidParam("query", ["<search query>"], "query is required for 'search' action") };
    }
    const matches = ctx.memory.search(q);
    if (matches.length === 0) {
      return { content: `No memories match '${q}'.` };
    }
    const previewLength = ctx.config.contextPreviewLength;
    let sb = "";
    for (const [k, e] of matches) {
      const preview = e.value.length > previewLength ? e.value.substring(0, previewLength) + "..." : e.value;
      sb += `  ${k}: "${preview}"\n`;
    }
    sb += `${matches.length} matches`;
    return { content: sb };
  }

  private merge(ctx: ToolContext, key: string, value: string, keys: string[]): ToolResult {
    if (keys.length < 2 || keys.length > 5) {
      return { content: invalidParam("keys", ["2-5 source keys"], "merge requires 2-5 source keys") };
    }
    if (!key) {
      return { content: invalidParam("key", ["<target key>"], "target key is required for 'merge' action") };
    }
    if (!value) {
      return { content: invalidParam("value", ["<merged value>"], "merged value is required for 'merge' action") };
    }

    const trimmedKeys: string[] = [];
    for (const k of keys) {
      const tk = k.trim();
      if (!ctx.memory.getMemories().has(tk)) {
        return { content: `Source key '${tk}' not found.` };
      }
      const entry = ctx.memory.get(tk);
      if (!entry || entry.importance !== "archive") {
        return { content: "merge can only combine existing archive memories; core entries are protected." };
      }
      if (trimmedKeys.includes(tk)) {
        return { content: `Duplicate source key '${tk}'.` };
      }
      trimmedKeys.push(tk);
    }

    const targetKey = key.trim();
    const existingTarget = ctx.memory.get(targetKey);
    if (existingTarget && existingTarget.importance === "core") {
      return { content: `Target key '${targetKey}' is a core entry and cannot be overwritten by merge.` };
    }

    const before = snapshot(ctx.memory);
    for (const tk of trimmedKeys) {
      ctx.memory.forget(tk);
    }
    ctx.memory.set(targetKey, value, "archive", getSource(ctx));
    ctx.save();
    return {
      content: `Merged ${trimmedKeys.length} entries into '${targetKey}'`,
      memoryDiff: computeDiff(before, snapshot(ctx.memory)),
    };
  }
}
