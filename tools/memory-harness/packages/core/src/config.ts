export interface MemoryConfig {
  enabled: boolean;
  maxMemories: number;
  coreLimit: number;
  contextPreviewLength: number;
}

export const DEFAULT_MEMORY_CONFIG: MemoryConfig = {
  enabled: true,
  maxMemories: 50,
  coreLimit: 10,
  contextPreviewLength: 30,
};
