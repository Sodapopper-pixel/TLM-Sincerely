import { describe, it, expect } from "vitest";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import {
  createMockTransport,
  createRecordTransport,
  createReplayTransport,
  mockFinal,
  mockToolCall,
} from "../src/index.js";

describe("LLM transport record/replay round-trip", () => {
  it("record writes turns; replay reproduces the same responses offline", async () => {
    const dir = mkdtempSync(resolve(tmpdir(), "harness-rec-"));
    const inner = createMockTransport([
      mockToolCall("c1", "tlm_memory", { action: "recall", key: "x" }),
      mockFinal("done"),
    ]);
    const recorder = createRecordTransport(inner, dir, "sess");
    const r1 = await recorder.chat([], [], { temperature: 0 });
    const r2 = await recorder.chat([], [], { temperature: 0 });
    expect(r1.tool_calls?.[0]?.function.name).toBe("tlm_memory");
    expect(r2.content).toBe("done");

    const replayer = createReplayTransport(dir, "sess");
    const p1 = await replayer.chat([], [], { temperature: 0 });
    const p2 = await replayer.chat([], [], { temperature: 0 });
    expect(p1.tool_calls?.[0]?.function.name).toBe("tlm_memory");
    expect(p2.content).toBe("done");
  });

  it("replay throws on missing recording", async () => {
    const dir = mkdtempSync(resolve(tmpdir(), "harness-rec-empty-"));
    const replayer = createReplayTransport(dir, "no-such-session");
    await expect(replayer.chat([], [], { temperature: 0 })).rejects.toThrow();
  });
});
