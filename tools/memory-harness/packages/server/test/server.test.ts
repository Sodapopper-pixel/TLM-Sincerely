import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { mkdtempSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  createMockTransport,
  mockFinal,
  mockToolCall,
  type LLMMessage,
  type LLMRequestOptions,
  type LLMResponse,
  type LLMTransport,
  type ToolSchema,
} from "@tlm-harness/agent";
import { createHarnessServer, type HarnessServer, type Session } from "../src/index.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const HARNESS_ROOT = resolve(HERE, "..", "..", "..");
const SKILLS_DIR = resolve(HARNESS_ROOT, "fixtures", "skills");

interface DelayedTransportState {
  started: number;
  completed: number;
  aborted: number;
}

function abortError(): Error {
  const error = new Error("aborted");
  error.name = "AbortError";
  return error;
}

function createAbortableDelayedTransport(
  delayMs: number,
  responses: LLMResponse[],
): { transport: LLMTransport; state: DelayedTransportState } {
  let turn = 0;
  const state: DelayedTransportState = { started: 0, completed: 0, aborted: 0 };
  const transport: LLMTransport = {
    chat(
      _messages: LLMMessage[],
      _tools: ToolSchema[],
      options: LLMRequestOptions,
    ): Promise<LLMResponse> {
      state.started++;
      const response = responses[turn] ?? mockFinal("");
      turn++;
      return new Promise<LLMResponse>((resolveResponse, rejectResponse) => {
        let timer: ReturnType<typeof setTimeout> | undefined;
        const onAbort = (): void => {
          if (timer !== undefined) {
            clearTimeout(timer);
          }
          options.signal?.removeEventListener("abort", onAbort);
          state.aborted++;
          rejectResponse(abortError());
        };
        if (options.signal?.aborted) {
          onAbort();
          return;
        }
        timer = setTimeout(() => {
          options.signal?.removeEventListener("abort", onAbort);
          state.completed++;
          resolveResponse(response);
        }, delayMs);
        options.signal?.addEventListener("abort", onAbort, { once: true });
      });
    },
  };
  return { transport, state };
}

async function waitFor(predicate: () => boolean, timeoutMs = 2_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    if (Date.now() >= deadline) {
      throw new Error("Timed out waiting for condition");
    }
    await new Promise<void>((resolveDelay) => setTimeout(resolveDelay, 10));
  }
}

async function startHarness(
  transportFactory?: (session: Session) => LLMTransport,
): Promise<{ harness: HarnessServer; baseUrl: string; dataDir: string }> {
  const dataDir = mkdtempSync(resolve(tmpdir(), "harness-data-"));
  const harness = createHarnessServer({
    dataDir,
    skillsDir: SKILLS_DIR,
    recordingsDir: mkdtempSync(resolve(tmpdir(), "harness-rec-")),
    host: "127.0.0.1",
    port: 0,
    transportFactory,
  });
  await harness.start();
  const addr = harness.server.address() as { port: number };
  return { harness, baseUrl: `http://127.0.0.1:${addr.port}`, dataDir };
}

async function post(baseUrl: string, path: string, body: unknown): Promise<Response> {
  return fetch(`${baseUrl}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

async function createSession(baseUrl: string): Promise<string> {
  const res = await post(baseUrl, "/api/sessions", {});
  expect(res.status).toBe(201);
  return ((await res.json()) as { id: string }).id;
}

const harness = createHarnessServer({
  dataDir: mkdtempSync(resolve(tmpdir(), "harness-data-default-")),
  skillsDir: SKILLS_DIR,
  recordingsDir: mkdtempSync(resolve(tmpdir(), "harness-rec-default-")),
  host: "127.0.0.1",
  port: 0,
  transportFactory: () =>
    createMockTransport([
      mockToolCall("c1", "tlm_memory", {
        action: "remember",
        key: "player_hobby",
        value: "reading",
        importance: "core",
      }),
      mockFinal("Got it, I'll remember that."),
    ]),
});

let baseUrl = "";

beforeAll(async () => {
  await harness.start();
  baseUrl = `http://127.0.0.1:${(harness.server.address() as { port: number }).port}`;
});

afterAll(async () => {
  await harness.stop();
});

describe("server / sessions + chat SSE pipeline", () => {
  it("creates a session, runs a tool loop, persists memory, and exposes a trace", async () => {
    const id = await createSession(baseUrl);
    const chatRes = await post(baseUrl, `/api/sessions/${id}/chat`, {
      message: "remember my hobby is reading",
    });
    const text = await chatRes.text();
    expect(text).toContain("event: context");
    expect(text).toContain("event: tool_call");
    expect(text).toContain("event: memory_diff");
    expect(text).toContain("Got it, I'll remember that.");
    expect(text).toContain("event: done");

    const memory = (await (await fetch(`${baseUrl}/api/sessions/${id}/memory`)).json()) as {
      memories: { player_hobby: { value: string; importance: string } };
    };
    expect(memory.memories.player_hobby).toMatchObject({ value: "reading", importance: "core" });
  });
});

describe("server / confirmed memory CRUD persistence", () => {
  it("does not acknowledge PUT, DELETE, or import until the memory file is persisted", async () => {
    const { harness: isolated, baseUrl: isolatedBase, dataDir } = await startHarness(() => createMockTransport([]));
    try {
      const id = await createSession(isolatedBase);
      const file = join(dataDir, "maid_memories", `${id}.json`);

      const put = await fetch(`${isolatedBase}/api/sessions/${id}/memory`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ key: "k1", value: "v1", importance: "archive" }),
      });
      expect(put.status).toBe(200);
      expect((await readFile(file, "utf8"))).toContain('"k1"');

      const deleted = await fetch(`${isolatedBase}/api/sessions/${id}/memory/k1`, { method: "DELETE" });
      expect(deleted.status).toBe(200);
      expect((await readFile(file, "utf8"))).not.toContain('"k1"');

      const imported = await post(isolatedBase, `/api/sessions/${id}/memory/import`, {
        json: JSON.stringify({
          memories: {
            imported_key: {
              value: "imported value",
              importance: "core",
              createdAt: 0,
              updatedAt: 0,
              lastAccessedAt: 0,
              accessCount: 0,
              source: "",
            },
          },
          meta: { lastTidyAt: 0 },
        }),
      });
      expect(imported.status).toBe(200);
      expect((await readFile(file, "utf8"))).toContain('"imported_key"');
    } finally {
      await isolated.stop();
    }
  });
});

describe("server / schema validation and transactional scenario loads", () => {
  it("rejects unknown env/config fields and invalid enum values", async () => {
    const id = await createSession(baseUrl);
    const badEnv = await fetch(`${baseUrl}/api/sessions/${id}/env`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ weather: "sunny", unknownField: true }),
    });
    expect(badEnv.status).toBe(400);
    expect(((await badEnv.json()) as { error: string }).error).toContain("unknown env fields");

    const badConfig = await fetch(`${baseUrl}/api/sessions/${id}/config`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ maxMemories: -1 }),
    });
    expect(badConfig.status).toBe(400);
  });

  it("leaves the original config and memory untouched when a scenario cannot fit its new limit", async () => {
    const id = await createSession(baseUrl);
    await fetch(`${baseUrl}/api/sessions/${id}/memory`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ key: "original", value: "keep me", importance: "core" }),
    });

    const load = await post(baseUrl, `/api/sessions/${id}/load-scenario`, {
      config: { maxMemories: 1 },
      memory: [
        { key: "one", value: "1", importance: "archive" },
        { key: "two", value: "2", importance: "archive" },
      ],
    });
    expect(load.status).toBe(400);

    const config = (await (await fetch(`${baseUrl}/api/sessions/${id}/config`)).json()) as { maxMemories: number };
    expect(config.maxMemories).toBe(50);
    const memory = (await (await fetch(`${baseUrl}/api/sessions/${id}/memory`)).json()) as {
      memories: Record<string, unknown>;
    };
    expect(memory.memories.original).toBeDefined();
  });

  it("rejects shrinking maxMemories below retained memory size", async () => {
    const id = await createSession(baseUrl);
    for (const key of ["one", "two"]) {
      const response = await fetch(`${baseUrl}/api/sessions/${id}/memory`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ key, value: key, importance: "archive" }),
      });
      expect(response.status).toBe(200);
    }

    const load = await post(baseUrl, `/api/sessions/${id}/load-scenario`, {
      config: { maxMemories: 1 },
    });
    expect(load.status).toBe(400);

    const config = (await (await fetch(`${baseUrl}/api/sessions/${id}/config`)).json()) as { maxMemories: number };
    expect(config.maxMemories).toBe(50);
    const memory = (await (await fetch(`${baseUrl}/api/sessions/${id}/memory`)).json()) as {
      memories: Record<string, unknown>;
    };
    expect(Object.keys(memory.memories)).toEqual(expect.arrayContaining(["one", "two"]));
  });

  it("rejects direct config shrink below retained memory size", async () => {
    const id = await createSession(baseUrl);
    for (const key of ["one", "two"]) {
      await fetch(`${baseUrl}/api/sessions/${id}/memory`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ key, value: key, importance: "archive" }),
      });
    }

    const patch = await fetch(`${baseUrl}/api/sessions/${id}/config`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ maxMemories: 1 }),
    });
    expect(patch.status).toBe(400);

    const config = (await (await fetch(`${baseUrl}/api/sessions/${id}/config`)).json()) as { maxMemories: number };
    expect(config.maxMemories).toBe(50);
  });
});

describe("server / chat serialization and transport lifecycle", () => {
  it("returns 409 for a real concurrent chat on the same harness and SessionManager", async () => {
    const delayed = createAbortableDelayedTransport(300, [mockFinal("first chat")]);
    const { harness: isolated, baseUrl: isolatedBase } = await startHarness(() => delayed.transport);
    try {
      const id = await createSession(isolatedBase);
      const first = post(isolatedBase, `/api/sessions/${id}/chat`, { message: "first" });
      await waitFor(() => delayed.state.started === 1);

      const second = await post(isolatedBase, `/api/sessions/${id}/chat`, { message: "second" });
      expect(second.status).toBe(409);

      const firstResponse = await first;
      expect(firstResponse.status).toBe(200);
      expect(await firstResponse.text()).toContain("first chat");
    } finally {
      await isolated.stop();
    }
  });

  it("caches a transportFactory main-chat transport per session but uses a fresh one for auto-gen", async () => {
    let created = 0;
    const mainTransport = createMockTransport([mockFinal("first chat"), mockFinal("second chat")]);
    const autoGenTransport = createMockTransport([mockFinal("generated setting")]);
    const { harness: isolated, baseUrl: isolatedBase } = await startHarness(() => {
      created++;
      return created === 1 ? mainTransport : autoGenTransport;
    });
    try {
      const id = await createSession(isolatedBase);
      expect(await (await post(isolatedBase, `/api/sessions/${id}/chat`, { message: "one" })).text()).toContain("first chat");
      expect(await (await post(isolatedBase, `/api/sessions/${id}/auto-gen-setting`, {})).json()).toEqual({ setting: "generated setting" });
      expect(await (await post(isolatedBase, `/api/sessions/${id}/chat`, { message: "two" })).text()).toContain("second chat");
      expect(created).toBe(2);
    } finally {
      await isolated.stop();
    }
  });
});

describe("server / SSE disconnect", () => {
  it("propagates client response closure through AbortSignal and stops the delayed transport early", async () => {
    const delayed = createAbortableDelayedTransport(5_000, [mockFinal("must not complete")]);
    const { harness: isolated, baseUrl: isolatedBase } = await startHarness(() => delayed.transport);
    try {
      const id = await createSession(isolatedBase);
      const request = post(isolatedBase, `/api/sessions/${id}/chat`, { message: "disconnect" });
      await waitFor(() => delayed.state.started === 1);
      const response = await request;
      const reader = response.body?.getReader();
      expect(reader).toBeDefined();
      // Cancelling the active response body closes the client-side SSE stream.
      // The server must observe res.close and propagate AbortSignal downstream.
      await reader?.cancel();

      await waitFor(() => delayed.state.aborted === 1);
      expect(delayed.state.completed).toBe(0);
      await reader?.cancel();
    } finally {
      await isolated.stop();
    }
  });
});
