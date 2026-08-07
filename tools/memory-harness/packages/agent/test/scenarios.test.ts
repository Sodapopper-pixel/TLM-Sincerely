import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { DEFAULT_MEMORY_CONFIG, MaidMemory } from "@tlm-harness/core";
import {
  DEFAULT_LOOP_CONFIG,
  DEFAULT_MAID,
  MemoryTool,
  QueryContextTool,
  ToolRegistry,
  UseSkillTool,
  createMockTransport,
  runAgentLoop,
  type LLMResponse,
  type ToolContext,
} from "../src/index.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const SCENARIOS_DIR = resolve(HERE, "..", "..", "..", "fixtures", "scenarios");

interface Scenario {
  id: string;
  intent: string;
  env: Record<string, unknown>;
  initialMemory: Array<{ key: string; value: string; importance: string }>;
  opening: string;
  recording: LLMResponse[];
  assertions: {
    toolCalls?: string[];
    memoryHas?: Record<string, string>;
    memoryAbsent?: string[];
    finalContains?: string[];
    contextContains?: string[];
  };
}

function loadScenarios(): Scenario[] {
  return readdirSync(SCENARIOS_DIR)
    .filter((f) => f.endsWith(".json"))
    .map((f) => JSON.parse(readFileSync(resolve(SCENARIOS_DIR, f), "utf8")) as Scenario);
}

function makeRegistry(): ToolRegistry {
  const r = new ToolRegistry();
  r.register(new MemoryTool());
  r.register(new UseSkillTool());
  r.register(new QueryContextTool());
  return r;
}

describe("scenario regression (offline, scripted recordings)", () => {
  const scenarios = loadScenarios();
  for (const sc of scenarios) {
    it(`scenario ${sc.id}: ${sc.intent}`, async () => {
      const memory = new MaidMemory(DEFAULT_MEMORY_CONFIG.maxMemories);
      for (const e of sc.initialMemory) {
        memory.set(e.key, e.value, e.importance);
      }
      const maid = { ...DEFAULT_MAID, ...(sc.env as object) };
      const ctx: ToolContext = {
        memory,
        config: { ...DEFAULT_MEMORY_CONFIG },
        maid,
        skills: [],
        save: () => {},
      };
      const deps = {
        transport: createMockTransport(sc.recording),
        registry: makeRegistry(),
        ctx,
        systemPrompt: maid.systemPrompt,
        loopConfig: DEFAULT_LOOP_CONFIG,
        emit: () => {},
      };
      const out = await runAgentLoop(deps, [], sc.opening);
      const a = sc.assertions;

      if (a.toolCalls) {
        const names = out.trace.toolLogs.map((t) => t.name);
        for (const expected of a.toolCalls) {
          expect(names, `toolCalls should include ${expected}`).toContain(expected);
        }
      }
      if (a.memoryHas) {
        for (const [k, v] of Object.entries(a.memoryHas)) {
          expect(memory.get(k)?.value, `memory ${k}`).toBe(v);
        }
      }
      if (a.memoryAbsent) {
        for (const k of a.memoryAbsent) {
          expect(memory.get(k), `memory ${k} should be absent`).toBeUndefined();
        }
      }
      if (a.finalContains) {
        for (const s of a.finalContains) {
          expect(out.finalText).toContain(s);
        }
      }
      if (a.contextContains) {
        const userMsg = out.trace.messages.find(
          (m) => m.role === "user" && m.content !== null && m.content.includes("<context>"),
        );
        for (const s of a.contextContains) {
          expect(userMsg?.content, `context should contain ${s}`).toContain(s);
        }
      }
    });
  }
});
