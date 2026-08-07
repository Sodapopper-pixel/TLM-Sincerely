import type { MaidMemory, MemoryEntry } from "@tlm-harness/core";
import type { ToolSchema } from "../types.js";
import { type MemoryDiff, type Tool, type ToolContext, type ToolResult, invalidParam } from "../tool-registry.js";

const TOOL_ID = "tlm_memory";
const TOOL_DESC = `Manage persistent key-value memories for the maid.
Use 'remember' to store important facts about the player (preferences, history, promises).
Use 'recall' to retrieve the full content of a specific memory.
Use 'forget' to delete a memory when the player asks or when it's no longer relevant.`;

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
              enum: ["remember", "recall", "forget"],
              description: "The action to perform on memories",
            },
            key: {
              type: "string",
              description:
                "The memory key (unique identifier for the memory). For 'remember' this may be a new key; for 'recall'/'forget' use an existing key (see preview or 'Available keys' in tool errors).",
            },
            value: { type: "string", description: "The memory value. Required for 'remember' action." },
            importance: {
              type: "string",
              enum: ["core", "archive"],
              description: "Memory importance: 'core' (always shown in full) or 'archive' (shown as preview)",
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
    switch (action) {
      case "remember":
        return this.remember(ctx, key, value, importance);
      case "recall":
        return this.recall(ctx, key);
      case "forget":
        return this.forget(ctx, key);
      default:
        return { content: invalidParam("action", ["remember", "recall", "forget"], `Unknown action: ${action}`) };
    }
  }

  private remember(ctx: ToolContext, key: string, value: string, importance: string): ToolResult {
    if (!key) {
      return { content: invalidParam("key", ["<memory key>"], "key is required for 'remember' action") };
    }
    if (!value) {
      return { content: invalidParam("value", ["<memory value>"], "value is required for 'remember' action") };
    }
    const before = snapshot(ctx.memory);
    const result = ctx.memory.set(key, value, importance);
    if (result === "rejected-full") {
      return { content: `Memory limit reached (${ctx.config.maxMemories} max). Delete some memories first with 'forget'.` };
    }
    if (result === "rejected-empty") {
      return { content: invalidParam("key", ["<memory key>"], "key is required for 'remember' action") };
    }
    ctx.save();
    return {
      content: `Remembered '${key}' = "${value}" (${importance})`,
      memoryDiff: computeDiff(before, snapshot(ctx.memory)),
    };
  }

  private recall(ctx: ToolContext, key: string): ToolResult {
    if (!key) {
      const keys = ctx.memory.keys();
      return {
        content: invalidParam("key", keys.length === 0 ? ["no memories stored"] : keys, "key is required for 'recall' action"),
      };
    }
    const entry = ctx.memory.get(key);
    if (!entry) {
      return { content: `Memory key '${key}' not found. Available keys: [${ctx.memory.keys().join(", ")}]` };
    }
    return { content: `[${entry.importance}] ${key}: ${entry.value}` };
  }

  private forget(ctx: ToolContext, key: string): ToolResult {
    if (!key) {
      const keys = ctx.memory.keys();
      return {
        content: invalidParam("key", keys.length === 0 ? ["no memories stored"] : keys, "key is required for 'forget' action"),
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
}
