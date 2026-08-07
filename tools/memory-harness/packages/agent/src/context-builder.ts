import { memoryContextValue, MEMORY_CONTEXT_LABEL } from "@tlm-harness/core";
import type { MaidMemory, MemoryConfig } from "@tlm-harness/core";
import type { VirtualMaid } from "./virtual-maid.js";

export interface ContextLine {
  label: string;
  value: string;
}

export function statusContext(maid: VirtualMaid): string {
  const riding = maid.riding && maid.riding !== "" ? maid.riding : "not";
  return `health=${maid.health}/${maid.maxHealth}, sleeping=${maid.isSleeping}, following=${maid.isFollowing}, sitting=${maid.sitting}, riding=${riding}, schedule=${maid.schedule}, activity=${maid.activity}, task=${maid.task}`;
}

// User category is promptContext=false in the main mod (on-demand via
// query_game_context tool only), so it is NOT auto-injected into <context>.
// Kept exported for a future on-demand query tool mirror.
export function userContext(maid: VirtualMaid): string {
  return `name=${maid.userName}, health=${maid.userHealth}`;
}

export function worldContext(maid: VirtualMaid): string {
  return `time=${maid.timeOfDay}, weather=${maid.weather}, dimension=${maid.dimension}, biome=${maid.biome}`;
}

export function buildContextLines(maid: VirtualMaid, memory: MaidMemory, config: MemoryConfig): ContextLine[] {
  return [
    { label: "Status", value: statusContext(maid) },
    { label: "World", value: worldContext(maid) },
    { label: MEMORY_CONTEXT_LABEL, value: memoryContextValue(memory, config) },
  ];
}

export function buildContextBlock(maid: VirtualMaid, memory: MaidMemory, config: MemoryConfig): string {
  const lines = buildContextLines(maid, memory, config);
  const body = lines.map((l) => `- ${l.label}: ${l.value}`).join("\n");
  return `<context>\n${body}\n</context>`;
}

export function buildUserMessage(
  maid: VirtualMaid,
  memory: MaidMemory,
  config: MemoryConfig,
  userText: string,
): string {
  return `${buildContextBlock(maid, memory, config)}\n${userText}`;
}
