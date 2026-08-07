import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { resolve } from "node:path";
import { tmpdir } from "node:os";
import { mkdtempSync } from "node:fs";
import { createMockTransport, mockFinal, mockToolCall } from "@tlm-harness/agent";
import { createHarnessServer } from "../src/index.js";

const harness = createHarnessServer({
  dataDir: mkdtempSync(resolve(tmpdir(), "harness-data-")),
  skillsDir: resolve(process.cwd(), "fixtures/skills"),
  recordingsDir: mkdtempSync(resolve(tmpdir(), "harness-rec-")),
  host: "127.0.0.1",
  port: 0,
  transportFactory: () =>
    createMockTransport([
      mockToolCall("c1", "tlm_memory", { action: "remember", key: "player_hobby", value: "reading", importance: "core" }),
      mockFinal("Got it, I'll remember that."),
    ]),
});

let baseUrl = "";

beforeAll(async () => {
  await harness.start();
  const addr = harness.server.address();
  baseUrl = `http://127.0.0.1:${(addr as { port: number }).port}`;
});

afterAll(async () => {
  await harness.stop();
});

async function post(path: string, body: unknown): Promise<Response> {
  return fetch(`${baseUrl}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

async function createSession(): Promise<string> {
  const res = await post("/api/sessions", {});
  const data = (await res.json()) as { id: string };
  return data.id;
}

describe("server / sessions + chat SSE pipeline", () => {
  it("creates session, runs chat with tool loop, persists memory, exposes trace", async () => {
    const id = await createSession();
    const chatRes = await post(`/api/sessions/${id}/chat`, { message: "remember my hobby is reading" });
    const text = await chatRes.text();
    expect(text).toContain("event: context");
    expect(text).toContain("event: tool_call");
    expect(text).toContain("event: tool_result");
    expect(text).toContain("event: memory_diff");
    expect(text).toContain("event: final");
    expect(text).toContain("Got it, I'll remember that.");
    expect(text).toContain("event: done");

    const mem = (await (await fetch(`${baseUrl}/api/sessions/${id}/memory`)).json()) as {
      memories: { player_hobby: { value: string; importance: string } };
    };
    expect(mem.memories.player_hobby.value).toBe("reading");
    expect(mem.memories.player_hobby.importance).toBe("core");

    const trace = (await (await fetch(`${baseUrl}/api/sessions/${id}/trace`)).json()) as {
      toolLogs: unknown[];
    };
    expect(trace.toolLogs.length).toBe(1);
  });
});

describe("server / memory + env + config CRUD", () => {
  it("manual set/get/delete memory and patch env/config", async () => {
    const id = await createSession();
    await fetch(`${baseUrl}/api/sessions/${id}/memory`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ key: "k1", value: "v1", importance: "archive" }),
    });
    let mem = (await (await fetch(`${baseUrl}/api/sessions/${id}/memory`)).json()) as {
      memories: { k1?: { value: string } };
    };
    expect(mem.memories.k1?.value).toBe("v1");

    await fetch(`${baseUrl}/api/sessions/${id}/memory/k1`, { method: "DELETE" });
    mem = (await (await fetch(`${baseUrl}/api/sessions/${id}/memory`)).json()) as {
      memories: { k1?: { value: string } };
    };
    expect(mem.memories.k1).toBeUndefined();

    const env = (await (
      await fetch(`${baseUrl}/api/sessions/${id}/env`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ weather: "rain" }),
      })
    ).json()) as { weather: string };
    expect(env.weather).toBe("rain");

    const cfg = (await (
      await fetch(`${baseUrl}/api/sessions/${id}/config`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ coreLimit: 3 }),
      })
    ).json()) as { coreLimit: number };
    expect(cfg.coreLimit).toBe(3);
  });

  it("exports memory in json/text/context formats", async () => {
    const id = await createSession();
    await fetch(`${baseUrl}/api/sessions/${id}/memory`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ key: "fav", value: "tea", importance: "core" }),
    });
    const ctx = (await (
      await fetch(`${baseUrl}/api/sessions/${id}/memory/export?format=context`)
    ).json()) as { content: string };
    expect(ctx.content).toContain("fav: tea");
  });
});
