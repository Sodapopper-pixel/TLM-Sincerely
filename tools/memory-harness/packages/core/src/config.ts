export interface MemoryConfig {
  enabled: boolean;
  maxMemories: number;
  coreLimit: number;
  contextPreviewLength: number;
  autoEvict: boolean;
  memoryGuidance: boolean;
  tidyEnabled: boolean;
  tidyThreshold: number;
  tidyCooldownMinutes: number;
  showSource: boolean;
  previewMode: "full" | "keys_only";
}

export const DEFAULT_MEMORY_CONFIG: MemoryConfig = {
  enabled: true,
  maxMemories: 50,
  coreLimit: 10,
  contextPreviewLength: 30,
  autoEvict: true,
  memoryGuidance: true,
  tidyEnabled: true,
  tidyThreshold: 0.8,
  tidyCooldownMinutes: 20,
  showSource: false,
  previewMode: "full",
};
