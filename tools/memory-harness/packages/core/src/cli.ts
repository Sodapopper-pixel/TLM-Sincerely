import { readFileSync, writeFileSync } from "node:fs";
import { MaidMemory } from "./memory.js";
import { DEFAULT_MEMORY_CONFIG, type MemoryConfig } from "./config.js";
import { serialize, deserialize } from "./store.js";
import { memoryContextValue, MEMORY_CONTEXT_LABEL } from "./context.js";

function usage(): never {
  console.error(`Usage:
  cli dump-context <file> [--core-limit N] [--preview-length N] [--disabled]
  cli memory-set <file> <key> <value> [core|archive]
  cli memory-list <file>
  cli memory-export <file> [json|text|context]`);
  process.exit(1);
}

function loadFromFile(path: string): MaidMemory {
  return deserialize(readFileSync(path, "utf8"));
}

function saveToFile(path: string, m: MaidMemory): void {
  writeFileSync(path, serialize(m), "utf8");
}

function flagValue(args: string[], flag: string, dflt: number): number {
  const i = args.indexOf(flag);
  if (i >= 0 && i + 1 < args.length) {
    return Number(args[i + 1]);
  }
  return dflt;
}

function main(argv: string[]): void {
  const [cmd, file, ...rest] = argv;
  if (!cmd || !file) {
    usage();
  }

  if (cmd === "dump-context") {
    const m = loadFromFile(file);
    const config: MemoryConfig = { ...DEFAULT_MEMORY_CONFIG };
    config.coreLimit = flagValue(rest, "--core-limit", config.coreLimit);
    config.contextPreviewLength = flagValue(rest, "--preview-length", config.contextPreviewLength);
    if (rest.includes("--disabled")) {
      config.enabled = false;
    }
    console.log(`- ${MEMORY_CONTEXT_LABEL}: ${memoryContextValue(m, config)}`);
    return;
  }

  if (cmd === "memory-set") {
    const [key, value, impRaw] = rest;
    if (key === undefined || value === undefined) {
      usage();
    }
    const imp = impRaw ?? "archive";
    const m = loadFromFile(file);
    m.set(key, value, imp);
    saveToFile(file, m);
    console.log(`Set '${key.trim()}' = "${value.trim()}" (${imp})`);
    return;
  }

  if (cmd === "memory-list") {
    const m = loadFromFile(file);
    for (const k of m.keys()) {
      const e = m.get(k);
      console.log(`[${e?.importance ?? "archive"}] ${k}: ${e?.value ?? ""}`);
    }
    return;
  }

  if (cmd === "memory-export") {
    const m = loadFromFile(file);
    const fmt = rest[0] ?? "json";
    if (fmt === "json") {
      console.log(serialize(m));
    } else if (fmt === "context") {
      console.log(m.generateContextPreview(DEFAULT_MEMORY_CONFIG.coreLimit, DEFAULT_MEMORY_CONFIG.contextPreviewLength));
    } else {
      for (const [k, e] of m.entries()) {
        console.log(`[${e.importance}] ${k}: ${e.value}`);
      }
    }
    return;
  }

  usage();
}

main(process.argv.slice(2));
