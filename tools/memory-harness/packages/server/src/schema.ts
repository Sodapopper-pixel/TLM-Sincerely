const ENV_WHITELIST = new Set<string>([
  "name",
  "language",
  "systemPrompt",
  "health",
  "maxHealth",
  "isSleeping",
  "isFollowing",
  "sitting",
  "schedule",
  "activity",
  "task",
  "timeOfDay",
  "weather",
  "dimension",
  "biome",
  "userName",
  "userHealth",
  "modelDesc",
  "riding",
  "userMainHand",
  "equipment",
  "effects",
  "position",
  "nearby",
]);

const ENUM_ENV: Record<string, readonly string[]> = {
  weather: ["clear", "rain", "thunder", "snow"],
  dimension: ["overworld", "the_nether", "the_end"],
  schedule: ["DAY", "NIGHT", "ALL"],
  activity: ["idle", "work", "rest"],
};

const CONFIG_WHITELIST = new Set<string>([
  "enabled",
  "maxMemories",
  "coreLimit",
  "contextPreviewLength",
  "autoEvict",
  "memoryGuidance",
  "tidyEnabled",
  "tidyThreshold",
  "tidyCooldownMinutes",
  "showSource",
  "previewMode",
]);

const CONFIG_RANGES: Record<string, (v: unknown) => boolean> = {
  enabled: (v) => typeof v === "boolean",
  maxMemories: (v) => typeof v === "number" && Number.isInteger(v) && v > 0 && v <= 1000,
  coreLimit: (v) => typeof v === "number" && Number.isInteger(v) && v >= 0 && v <= 500,
  contextPreviewLength: (v) => typeof v === "number" && Number.isInteger(v) && v >= 0 && v <= 1000,
  autoEvict: (v) => typeof v === "boolean",
  memoryGuidance: (v) => typeof v === "boolean",
  tidyEnabled: (v) => typeof v === "boolean",
  tidyThreshold: (v) => typeof v === "number" && !Number.isNaN(v) && v > 0 && v <= 1,
  tidyCooldownMinutes: (v) => typeof v === "number" && Number.isInteger(v) && v >= 0 && v <= 10080,
  showSource: (v) => typeof v === "boolean",
  previewMode: (v) => v === "full" || v === "keys_only",
};

const SCENARIO_WHITELIST = new Set<string>([
  "id",
  "intent",
  "env",
  "initialMemory",
  "config",
  "memory",
  "history",
  "opening",
  "recording",
  "assertions",
]);

export class SchemaError extends Error {
  constructor(
    message: string,
    public readonly field?: string,
  ) {
    super(message);
    this.name = "SchemaError";
  }
}

export function validateEnv(env: unknown): Record<string, unknown> {
  if (!env || typeof env !== "object" || Array.isArray(env)) {
    throw new SchemaError("env must be an object", "env");
  }
  const record = env as Record<string, unknown>;
  const unknown = Object.keys(record).filter((k) => !ENV_WHITELIST.has(k));
  if (unknown.length > 0) {
    throw new SchemaError(`unknown env fields: ${unknown.join(", ")}`, "env");
  }
  for (const [key, allowed] of Object.entries(ENUM_ENV)) {
    const raw = record[key];
    if (raw !== undefined && !allowed.includes(String(raw))) {
      throw new SchemaError(`invalid ${key}: ${String(raw)}`, key);
    }
  }
  if (typeof record.timeOfDay === "number" && (record.timeOfDay < 0 || record.timeOfDay > 24000)) {
    throw new SchemaError(`timeOfDay out of range`, "timeOfDay");
  }
  if (
    typeof record.health === "number" &&
    (record.health < 0 || record.health > 20)
  ) {
    throw new SchemaError(`health out of range`, "health");
  }
  if (
    typeof record.maxHealth === "number" &&
    (record.maxHealth < 1 || record.maxHealth > 40)
  ) {
    throw new SchemaError(`maxHealth out of range`, "maxHealth");
  }
  return record;
}

export function validateConfig(
  config: unknown,
): Record<string, unknown> {
  if (!config || typeof config !== "object" || Array.isArray(config)) {
    throw new SchemaError("config must be an object", "config");
  }
  const record = config as Record<string, unknown>;
  const unknown = Object.keys(record).filter((k) => !CONFIG_WHITELIST.has(k));
  if (unknown.length > 0) {
    throw new SchemaError(`unknown config fields: ${unknown.join(", ")}`, "config");
  }
  for (const [key, check] of Object.entries(CONFIG_RANGES)) {
    if (record[key] !== undefined && !check(record[key])) {
      throw new SchemaError(`invalid ${key}: ${String(record[key])}`, key);
    }
  }
  return record;
}

export function validateScenarioBody(
  body: Record<string, unknown>,
): Record<string, unknown> {
  const unknown = Object.keys(body).filter((k) => !SCENARIO_WHITELIST.has(k));
  if (unknown.length > 0) {
    throw new SchemaError(`unknown scenario fields: ${unknown.join(", ")}`, "scenario");
  }
  if (body.env !== undefined) {
    body.env = validateEnv(body.env);
  }
  if (body.config !== undefined) {
    body.config = validateConfig(body.config);
  }
  if (body.memory !== undefined) {
    if (!Array.isArray(body.memory)) {
      throw new SchemaError("memory must be an array when provided in scenario", "memory");
    }
    for (let i = 0; i < (body.memory as unknown[]).length; i++) {
      const entry = (body.memory as unknown[])[i];
      if (!entry || typeof entry !== "object") {
        throw new SchemaError(`memory[${i}] must be an object`, "memory");
      }
      const obj = entry as Record<string, unknown>;
      if (typeof obj.key !== "string" || typeof obj.value !== "string") {
        throw new SchemaError(`memory[${i}] requires string key and value`, "memory");
      }
      if (
        obj.importance !== undefined &&
        obj.importance !== "core" &&
        obj.importance !== "archive"
      ) {
        throw new SchemaError(`memory[${i}] importance must be core|archive`, "memory");
      }
    }
  }
  if (body.history !== undefined && !Array.isArray(body.history)) {
    throw new SchemaError("history must be an array when provided", "history");
  }
  return body;
}
