import { describe, it, expect } from "vitest";
import { DEFAULT_MEMORY_CONFIG, MaidMemory } from "@tlm-harness/core";
import {
  DEFAULT_MAID,
  DEFAULT_LOOP_CONFIG,
  MemoryTool,
  QueryContextTool,
  ToolRegistry,
  UseSkillTool,
  createMockTransport,
  mockFinal,
  mockToolCall,
  parseSkill,
  runAgentLoop,
  type AgentLoopEvent,
  type ToolContext,
} from "../src/index.js";

const SKILL_MD = `---
name: memory-guidance
description: >
  Guides the maid on when and how to use the persistent memory system.
---
Use tlm_memory to manage persistent memories. Remember player preferences.`;

function makeCtx(memory: MaidMemory): ToolContext {
  return {
    memory,
    config: { ...DEFAULT_MEMORY_CONFIG },
    maid: { ...DEFAULT_MAID },
    skills: [parseSkill(SKILL_MD)!],
    save: () => {},
  };
}

function makeRegistry(): ToolRegistry {
  const r = new ToolRegistry();
  r.register(new MemoryTool());
  r.register(new UseSkillTool());
  r.register(new QueryContextTool());
  return r;
}

function collectEvents(): { events: AgentLoopEvent[]; emit: (e: AgentLoopEvent) => void } {
  const events: AgentLoopEvent[] = [];
  return { events, emit: (e) => events.push(e) };
}

describe("agent loop / remember + persist", () => {
  it("executes tlm_memory.remember, persists, emits memory_diff", async () => {
    const memory = new MaidMemory(50);
    const ctx = makeCtx(memory);
    const { events, emit } = collectEvents();
    const deps = {
      transport: createMockTransport([
        mockToolCall("c1", "tlm_memory", { action: "remember", key: "player_hobby", value: "reading", importance: "core" }),
        mockFinal("Got it, I'll remember that."),
      ]),
      registry: makeRegistry(),
      ctx,
      systemPrompt: DEFAULT_MAID.systemPrompt,
      loopConfig: DEFAULT_LOOP_CONFIG,
      emit,
    };
    const out = await runAgentLoop(deps, [], "Please remember my hobby is reading.");
    expect(out.finalText).toBe("Got it, I'll remember that.");
    expect(memory.get("player_hobby")?.value).toBe("reading");
    expect(memory.get("player_hobby")?.importance).toBe("core");
    expect(out.trace.toolLogs[0].result).toBe(`Remembered 'player_hobby' = "reading" (core)`);
    const diffs = events.filter((e) => e.type === "memory_diff");
    expect(diffs.length).toBe(1);
    expect((diffs[0] as { diff: { added: string[] } }).diff.added).toEqual(["player_hobby"]);
  });
});

describe("agent loop / context injection refreshes next round", () => {
  it("next round user message contains updated memory preview", async () => {
    const memory = new MaidMemory(50);
    const ctx = makeCtx(memory);
    const { emit: emit1 } = collectEvents();
    const deps1 = {
      transport: createMockTransport([
        mockToolCall("c1", "tlm_memory", { action: "remember", key: "player_hobby", value: "reading", importance: "core" }),
        mockFinal("ok"),
      ]),
      registry: makeRegistry(),
      ctx,
      systemPrompt: DEFAULT_MAID.systemPrompt,
      loopConfig: DEFAULT_LOOP_CONFIG,
      emit: emit1,
    };
    const out1 = await runAgentLoop(deps1, [], "remember my hobby is reading");
    const { emit: emit2 } = collectEvents();
    const deps2 = {
      transport: createMockTransport([mockFinal("sure")]),
      registry: makeRegistry(),
      ctx,
      systemPrompt: DEFAULT_MAID.systemPrompt,
      loopConfig: DEFAULT_LOOP_CONFIG,
      emit: emit2,
    };
    const out2 = await runAgentLoop(deps2, out1.newHistoryEntries, "what is my hobby?");
    const userMsg = out2.trace.messages.find(
      (m) => m.role === "user" && m.content !== null && m.content.includes("<context>"),
    );
    expect(userMsg?.content).toContain("player_hobby: reading");
  });
});

describe("agent loop / use_skill injects body", () => {
  it("use_skill returns the skill body as tool result", async () => {
    const memory = new MaidMemory(50);
    const ctx = makeCtx(memory);
    const { emit } = collectEvents();
    const deps = {
      transport: createMockTransport([
        mockToolCall("c1", "use_skill", { name: "memory-guidance" }),
        mockFinal("Understood."),
      ]),
      registry: makeRegistry(),
      ctx,
      systemPrompt: DEFAULT_MAID.systemPrompt,
      loopConfig: DEFAULT_LOOP_CONFIG,
      emit,
    };
    const out = await runAgentLoop(deps, [], "help me remember things");
    expect(out.trace.toolLogs[0].result).toBe("Use tlm_memory to manage persistent memories. Remember player preferences.");
  });
});

describe("agent loop / guards", () => {
  it("stops on repeated identical tool batch (max 2)", async () => {
    const memory = new MaidMemory(50);
    const ctx = makeCtx(memory);
    const { emit } = collectEvents();
    const tc = () => mockToolCall("c1", "tlm_memory", { action: "recall", key: "x" });
    const deps = {
      transport: createMockTransport([tc(), tc(), tc()]),
      registry: makeRegistry(),
      ctx,
      systemPrompt: DEFAULT_MAID.systemPrompt,
      loopConfig: DEFAULT_LOOP_CONFIG,
      emit,
    };
    const out = await runAgentLoop(deps, [], "recall x");
    expect(out.error).toContain("Repeated identical tool batch");
  });

  it("stops on tool turn count exceed (max 16)", async () => {
    const memory = new MaidMemory(50);
    const ctx = makeCtx(memory);
    const { emit } = collectEvents();
    const responses = Array.from({ length: 17 }, (_, i) => mockToolCall(`c${i}`, "tlm_memory", { action: "recall", key: `k${i}` }));
    const deps = {
      transport: createMockTransport(responses),
      registry: makeRegistry(),
      ctx,
      systemPrompt: DEFAULT_MAID.systemPrompt,
      loopConfig: DEFAULT_LOOP_CONFIG,
      emit,
    };
    const out = await runAgentLoop(deps, [], "recall many");
    expect(out.error).toContain("Tool turn count exceed max count: 16");
  });
});
