import type { MaidMemory, MemoryConfig } from "@tlm-harness/core";
import type { ToolSchema } from "./types.js";
import type { VirtualMaid } from "./virtual-maid.js";
import type { SkillInstance } from "./skill-loader.js";

export interface MemoryDiff {
  added: string[];
  updated: string[];
  removed: string[];
}

export interface ToolContext {
  memory: MaidMemory;
  config: MemoryConfig;
  maid: VirtualMaid;
  skills: SkillInstance[];
  save: () => void;
}

export interface ToolResult {
  content: string;
  memoryDiff?: MemoryDiff;
}

export interface Tool {
  readonly id: string;
  schema(): ToolSchema;
  execute(args: Record<string, unknown>, ctx: ToolContext): ToolResult;
}

export class ToolRegistry {
  private readonly tools = new Map<string, Tool>();

  register(tool: Tool): void {
    this.tools.set(tool.id, tool);
  }

  get(id: string): Tool | undefined {
    return this.tools.get(id);
  }

  schemas(): ToolSchema[] {
    return [...this.tools.values()].map((t) => t.schema());
  }
}

export function invalidParam(name: string, valid: string[], reason: string): string {
  return `Invalid parameter: ${reason}. Correct usage: ${name}: choose one of [${valid.join(",")}]`;
}
