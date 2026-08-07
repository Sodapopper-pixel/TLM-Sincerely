import type { MaidMemory } from "./memory.js";
import type { MemoryConfig } from "./config.js";

export const MEMORY_CONTEXT_LABEL = "Maid persistent memories";

export function memoryContextValue(memory: MaidMemory, config: MemoryConfig): string {
  if (!config.enabled) {
    return "Memory system disabled";
  }
  if (memory.isEmpty()) {
    return "None";
  }
  return memory.generateContextPreview(config.coreLimit, config.contextPreviewLength);
}
