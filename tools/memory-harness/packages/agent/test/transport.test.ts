import { afterEach, describe, expect, it } from "vitest";
import { mkdtempSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  createMockTransport,
  createRecordTransport,
  createReplayTransport,
  mockFinal,
  mockToolCall,
} from "../src/index.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const tempDirs: string[] = [];

function tempDir(prefix: string): string {
  const dir = mkdtempSync(resolve(HERE, `.${prefix}-`));
  tempDirs.push(dir);
  return dir;
}

afterEach(() => {
  for (const dir of tempDirs.splice(0)) {
    rmSync(dir, { recursive: true, force: true });
  }
});

describe("LLM transport record/replay round-trip", () => {
  it("record writes turns; replay reproduces the same responses offline", async () => {
    const dir = tempDir("harness-rec");
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
    const dir = tempDir("harness-rec-empty");
    const replayer = createReplayTransport(dir, "no-such-session");
    await expect(replayer.chat([], [], { temperature: 0 })).rejects.toThrow();
  });

  it("record/replay supports multiple sessions concurrently", async () => {
    const dir = tempDir("harness-rec-multi");
    const innerA = createMockTransport([mockFinal("a1"), mockFinal("a2")]);
    const innerB = createMockTransport([mockFinal("b1"), mockFinal("b2")]);

    const recA = createRecordTransport(innerA, dir, "sess-a");
    const recB = createRecordTransport(innerB, dir, "sess-b");

    const [ra1, rb1] = await Promise.all([
      recA.chat([], [], { temperature: 0 }),
      recB.chat([], [], { temperature: 0 }),
    ]);
    expect(ra1.content).toBe("a1");
    expect(rb1.content).toBe("b1");

    const repA = createReplayTransport(dir, "sess-a");
    const repB = createReplayTransport(dir, "sess-b");
    const [pa1, pb1] = await Promise.all([
      repA.chat([], [], { temperature: 0 }),
      repB.chat([], [], { temperature: 0 }),
    ]);
    expect(pa1.content).toBe("a1");
    expect(pb1.content).toBe("b1");
  });

  it("record/replay preserves turn cursor across multi-round chats", async () => {
    const dir = tempDir("harness-rec-cursor");
    const inner = createMockTransport([
      mockToolCall("c1", "tlm_memory", { action: "remember", key: "k1", value: "v1", importance: "core" }),
      mockFinal("first"),
      mockFinal("second"),
    ]);
    const recorder = createRecordTransport(inner, dir, "cursor-sess");

    const first = await recorder.chat([], [], { temperature: 0 });
    expect(first.tool_calls?.[0]?.function.name).toBe("tlm_memory");

    const second = await recorder.chat([], [], { temperature: 0 });
    expect(second.content).toBe("first");

    const third = await recorder.chat([], [], { temperature: 0 });
    expect(third.content).toBe("second");

    const replayer = createReplayTransport(dir, "cursor-sess");
    const rfirst = await replayer.chat([], [], { temperature: 0 });
    expect(rfirst.tool_calls?.[0]?.function.name).toBe("tlm_memory");

    const rsecond = await replayer.chat([], [], { temperature: 0 });
    expect(rsecond.content).toBe("first");

    const rthird = await replayer.chat([], [], { temperature: 0 });
    expect(rthird.content).toBe("second");
  });
});
