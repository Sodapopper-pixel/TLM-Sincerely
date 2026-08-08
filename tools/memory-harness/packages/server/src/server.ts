import { createServer as httpCreateServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { deserialize, MaidMemory, memoryContextValue, serialize } from "@tlm-harness/core";
import {
  DEFAULT_LOOP_CONFIG,
  MemoryTool,
  QueryContextTool,
  ToolRegistry,
  UseSkillTool,
  createLiveTransport,
  createMockTransport,
  createRecordTransport,
  createReplayTransport,
  generateSetting,
  mockFinal,
  runAgentLoop,
  trimHistory,
  type LLMTransport,
} from "@tlm-harness/agent";
import { existsSync, mkdirSync, readFileSync, readdirSync, unlinkSync, writeFileSync } from "node:fs";
import { resolve, sep } from "node:path";
import { loadSkills, SessionManager, type Session } from "./session.js";

const SUMMARIZE_PROMPT =
  "Please review our conversation so far and use tlm_memory remember to record anything you missed: things the player asked you to remember, lasting preferences, personal facts, significant events. Use short semantic English keys and one concise sentence with context. Do not record small talk or transient game state.";

export interface ServerOptions {
  dataDir: string;
  skillsDir: string;
  recordingsDir: string;
  host: string;
  port: number;
  transportFactory?: (session: Session) => LLMTransport;
}

export interface HarnessServer {
  server: Server;
  manager: SessionManager;
  start(): Promise<void>;
  stop(): Promise<void>;
}

function buildRegistry(): ToolRegistry {
  const r = new ToolRegistry();
  r.register(new MemoryTool());
  r.register(new UseSkillTool());
  r.register(new QueryContextTool());
  return r;
}

function defaultTransport(session: Session, recordingsDir: string): LLMTransport {
  const mode = process.env.LLM_TRANSPORT ?? "live";
  if (mode === "mock") {
    return createMockTransport([mockFinal("[mock] LLM not configured. Set LLM_API_KEY for real responses.")]);
  }
  if (mode === "replay") {
    return createReplayTransport(recordingsDir, session.id);
  }
  const live = createLiveTransport({
    baseURL: process.env.LLM_BASE_URL ?? "",
    apiKey: process.env.LLM_API_KEY ?? "",
    model: process.env.LLM_MODEL ?? "",
  });
  if (mode === "record") {
    return createRecordTransport(live, recordingsDir, session.id);
  }
  return live;
}

function summarize(s: Session) {
  return {
    id: s.id,
    maidName: s.maid.name,
    memoryCount: s.memory.size(),
    config: s.config,
    historyLength: s.history.length,
    tokenUsage: s.tokenUsage,
    autoGenSetting: s.autoGenSetting,
  };
}

function listScenarios(scenariosDir: string) {
  if (!existsSync(scenariosDir)) {
    return [];
  }
  return readdirSync(scenariosDir)
    .filter((f) => f.endsWith(".json"))
    .map((f) => {
      try {
        const data = JSON.parse(readFileSync(resolve(scenariosDir, f), "utf8")) as Record<string, unknown>;
        return { id: data.id ?? f.replace(/\.json$/, ""), file: f, ...data };
      } catch {
        return { file: f, error: "invalid" };
      }
    });
}

function listUserScenarios(dir: string): Array<{ name: string; savedAt: number }> {
  if (!existsSync(dir)) {
    return [];
  }
  return readdirSync(dir)
    .filter((f) => f.endsWith(".json"))
    .map((f) => {
      try {
        const data = JSON.parse(readFileSync(resolve(dir, f), "utf8")) as { name?: string; savedAt?: number };
        return { name: data.name ?? f.replace(/\.json$/, ""), savedAt: data.savedAt ?? 0 };
      } catch {
        return { name: f.replace(/\.json$/, ""), savedAt: 0 };
      }
    });
}

function listGameMemories(dir: string): Array<{ name: string; file: string }> {
  if (!existsSync(dir)) {
    return [];
  }
  return readdirSync(dir)
    .filter((f) => f.endsWith(".json"))
    .map((f) => ({ name: f.replace(/\.json$/, ""), file: f }));
}

const MAX_BODY_BYTES = 10 * 1024 * 1024;

async function readBody(req: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  let total = 0;
  for await (const c of req) {
    total += (c as Buffer).length;
    if (total > MAX_BODY_BYTES) {
      throw new BodyTooLargeError();
    }
    chunks.push(c as Buffer);
  }
  const text = Buffer.concat(chunks).toString("utf8");
  return text ? (JSON.parse(text) as Record<string, unknown>) : {};
}

class BodyTooLargeError extends Error {
  constructor() {
    super(`Request body exceeds ${MAX_BODY_BYTES} bytes`);
    this.name = "BodyTooLargeError";
  }
}

const LOCAL_HOSTS = new Set(["127.0.0.1", "localhost", "::1"]);

function isLocalOrigin(req: IncomingMessage): boolean {
  const origin = req.headers.origin;
  if (origin === undefined || origin === "") {
    return true;
  }
  try {
    const u = new URL(origin);
    return LOCAL_HOSTS.has(u.hostname);
  } catch {
    return false;
  }
}

function isWriteMethod(method: string): boolean {
  return method === "POST" || method === "PUT" || method === "PATCH" || method === "DELETE";
}

function safeJsonPath(dir: string, name: string): string | null {
  if (!/^[A-Za-z0-9_-]+$/.test(name)) {
    return null;
  }
  const base = dir.endsWith(sep) ? dir : dir + sep;
  const file = resolve(dir, `${name}.json`);
  return file.startsWith(base) ? file : null;
}

function safeScenarioPath(dir: string, name: string): string | null {
  return safeJsonPath(dir, name);
}

function sendJson(res: ServerResponse, code: number, data: unknown): void {
  res.writeHead(code, { "Content-Type": "application/json" });
  res.end(JSON.stringify(data));
}

export function createHarnessServer(options: ServerOptions): HarnessServer {
  const skills = loadSkills(options.skillsDir);
  const manager = new SessionManager(options.dataDir, skills);
  const scenariosDir = resolve(options.dataDir, "..", "fixtures", "scenarios");
  const userScenariosDir = resolve(options.dataDir, "user-scenarios");
  const gameMemoriesDir =
    process.env.TLM_GAME_MEM_DIR ??
    resolve(options.dataDir, "..", "..", "..", "run", "config", "tlm_sincerely", "maid_memories");

  async function runChatStream(session: Session, message: string, res: ServerResponse): Promise<void> {
    res.writeHead(200, {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
    });
    const registry = buildRegistry();
    const ctx = {
      memory: session.memory,
      config: session.config,
      maid: session.maid,
      skills: session.skills,
      maintaining: false,
      save: () => manager.saveMemory(session),
    };
    const transport = options.transportFactory ? options.transportFactory(session) : defaultTransport(session, options.recordingsDir);
    const deps = {
      transport,
      registry,
      ctx,
      systemPrompt: session.maid.systemPrompt,
      loopConfig: DEFAULT_LOOP_CONFIG,
      emit: (e: unknown): void => {
        const ev = e as { type: string };
        res.write(`event: ${ev.type}\ndata: ${JSON.stringify(e)}\n\n`);
      },
    };
    let outcome;
    try {
      outcome = await runAgentLoop(deps, session.history, message);
    } catch (e) {
      const msg = (e as Error).message;
      res.write(`event: error\ndata: ${JSON.stringify({ type: "error", message: msg })}\n\n`);
      res.write(`event: done\ndata: ${JSON.stringify({ error: msg })}\n\n`);
      res.end();
      return;
    }
    session.history = trimHistory([...session.history, ...outcome.newHistoryEntries], DEFAULT_LOOP_CONFIG.historyLimit);
    session.lastTrace = outcome.trace;
    session.tokenUsage.promptTokens += outcome.trace.promptTokens;
    session.tokenUsage.completionTokens += outcome.trace.completionTokens;
    session.tokenUsage.totalTokens += outcome.trace.promptTokens + outcome.trace.completionTokens;
    session.tokenUsage.chatCount += 1;
    manager.saveMemory(session);
    res.write(`event: done\ndata: ${JSON.stringify({ finalText: outcome.finalText, error: outcome.error })}\n\n`);
    res.end();
  }

  function applyScenario(session: Session, body: Record<string, unknown>): void {
    const env = body.env as Record<string, unknown> | undefined;
    if (env) {
      session.maid = { ...session.maid, ...(env as object) } as Session["maid"];
    }
    const cfg = body.config as Record<string, unknown> | undefined;
    if (cfg) {
      session.config = { ...session.config, ...cfg } as Session["config"];
    }
    if (body.memory !== undefined) {
      if (Array.isArray(body.memory)) {
        session.memory = new MaidMemory(session.config.maxMemories);
        for (const e of body.memory as Array<Record<string, string>>) {
          session.memory.set(e.key, e.value, e.importance);
        }
      } else {
        session.memory = deserialize(JSON.stringify(body.memory), session.config.maxMemories);
      }
      manager.saveMemory(session);
    }
    const history = body.history as unknown[] | undefined;
    if (Array.isArray(history)) {
      session.history = history as Session["history"];
    }
  }

  const server = httpCreateServer(async (req: IncomingMessage, res: ServerResponse) => {
    const url = new URL(req.url ?? "/", "http://localhost");
    const seg = url.pathname.split("/").filter(Boolean);
    const method = req.method ?? "GET";
    try {
      if (isWriteMethod(method) && !isLocalOrigin(req)) {
        return sendJson(res, 403, { error: "forbidden: non-local origin" });
      }
      if (seg[0] !== "api") {
        return sendJson(res, 404, { error: "not found", path: url.pathname });
      }
      if (seg[1] === "scenarios" && method === "GET") {
        return sendJson(res, 200, listScenarios(scenariosDir));
      }
      if (seg[1] === "user-scenarios") {
        if (seg.length === 2 && method === "GET") {
          return sendJson(res, 200, listUserScenarios(userScenariosDir));
        }
        if (seg.length === 3) {
          const name = decodeURIComponent(seg[2]);
          const file = safeScenarioPath(userScenariosDir, name);
          if (file === null) {
            return sendJson(res, 400, { error: "invalid scenario name" });
          }
          if (method === "GET") {
            if (!existsSync(file)) {
              return sendJson(res, 404, { error: "user scenario not found" });
            }
            return sendJson(res, 200, JSON.parse(readFileSync(file, "utf8")));
          }
          if (method === "DELETE") {
            if (existsSync(file)) {
              unlinkSync(file);
            }
            return sendJson(res, 200, { ok: true });
          }
        }
        return sendJson(res, 404, { error: "not found", path: url.pathname });
      }
      if (seg[1] === "game-memories") {
        if (seg.length === 2 && method === "GET") {
          return sendJson(res, 200, listGameMemories(gameMemoriesDir));
        }
        if (seg.length === 3 && method === "GET") {
          const name = decodeURIComponent(seg[2]);
          const file = safeJsonPath(gameMemoriesDir, name);
          if (file === null) {
            return sendJson(res, 400, { error: "invalid memory file name" });
          }
          if (!existsSync(file)) {
            return sendJson(res, 404, { error: "game memory not found" });
          }
          return sendJson(res, 200, { name, content: readFileSync(file, "utf8") });
        }
        return sendJson(res, 404, { error: "not found", path: url.pathname });
      }
      if (seg[1] !== "sessions") {
        return sendJson(res, 404, { error: "not found", path: url.pathname });
      }
      if (seg.length === 2) {
        if (method === "POST") {
          return sendJson(res, 201, summarize(manager.create()));
        }
        if (method === "GET") {
          return sendJson(res, 200, manager.list().map(summarize));
        }
      }
      const session = manager.get(seg[2] ?? "");
      if (seg.length === 3) {
        if (!session) {
          return sendJson(res, 404, { error: "session not found" });
        }
        if (method === "GET") {
          return sendJson(res, 200, summarize(session));
        }
        if (method === "DELETE") {
          manager.delete(session.id);
          return sendJson(res, 200, { ok: true });
        }
      }
      if (!session || seg.length < 4) {
        return sendJson(res, 404, { error: "session not found" });
      }
      const sub = seg[3];
      if (sub === "chat" && method === "POST") {
        const body = await readBody(req);
        return runChatStream(session, String(body.message ?? ""), res);
      }
      if (sub === "summarize" && method === "POST") {
        return runChatStream(session, SUMMARIZE_PROMPT, res);
      }
      if (sub === "memory") {
        if (seg.length === 4 && method === "GET") {
          return sendJson(res, 200, JSON.parse(serialize(session.memory)));
        }
        if (seg.length === 4 && method === "PUT") {
          const b = await readBody(req);
          const result = session.memory.set(String(b.key ?? ""), String(b.value ?? ""), String(b.importance ?? "archive"));
          if (result === "rejected-full" || result === "rejected-empty") {
            return sendJson(res, 409, { ok: false, reason: result });
          }
          manager.saveMemory(session);
          return sendJson(res, 200, { ok: true, result });
        }
        if (seg.length === 5 && method === "DELETE") {
          session.memory.forget(decodeURIComponent(seg[4]));
          manager.saveMemory(session);
          return sendJson(res, 200, { ok: true });
        }
        if (seg.length === 5 && seg[4] === "import" && method === "POST") {
          const b = await readBody(req);
          const json = typeof b.json === "string" ? b.json : JSON.stringify(b.json ?? b);
          session.memory = deserialize(json, session.config.maxMemories);
          manager.saveMemory(session);
          return sendJson(res, 200, { ok: true });
        }
        if (seg.length === 5 && seg[4] === "export" && method === "GET") {
          const fmt = url.searchParams.get("format") ?? "json";
          if (fmt === "json") {
            return sendJson(res, 200, { format: "json", content: serialize(session.memory) });
          }
          if (fmt === "context") {
            return sendJson(res, 200, { format: "context", content: memoryContextValue(session.memory, session.config) });
          }
          const lines = [...session.memory.entries()].map(([k, e]) => `[${e.importance}] ${k}: ${e.value}`);
          return sendJson(res, 200, { format: "text", content: lines.join("\n") });
        }
      }
      if (sub === "env") {
        if (method === "GET") {
          return sendJson(res, 200, session.maid);
        }
        if (method === "PATCH") {
          const b = await readBody(req);
          session.maid = { ...session.maid, ...(b as object) } as Session["maid"];
          return sendJson(res, 200, session.maid);
        }
      }
      if (sub === "config") {
        if (method === "GET") {
          return sendJson(res, 200, session.config);
        }
        if (method === "PATCH") {
          const b = await readBody(req);
          const prevMax = session.config.maxMemories;
          session.config = { ...session.config, ...(b as object) } as Session["config"];
          if (session.config.maxMemories !== prevMax) {
            session.memory.setMaxMemories(session.config.maxMemories);
          }
          return sendJson(res, 200, session.config);
        }
      }
      if (sub === "trace" && method === "GET") {
        return sendJson(res, 200, session.lastTrace ?? { messages: [], toolLogs: [] });
      }
      if (sub === "load-scenario" && method === "POST") {
        const b = await readBody(req);
        applyScenario(session, b);
        return sendJson(res, 200, { ok: true });
      }
      if (sub === "save-scenario" && method === "POST") {
        const b = await readBody(req);
        const name = String(b.name ?? "").trim();
        if (name === "") {
          return sendJson(res, 400, { error: "scenario name is required" });
        }
        const file = safeScenarioPath(userScenariosDir, name);
        if (file === null) {
          return sendJson(res, 400, { error: "invalid scenario name" });
        }
        mkdirSync(userScenariosDir, { recursive: true });
        const content = {
          name,
          savedAt: Date.now(),
          env: { ...session.maid },
          config: { ...session.config },
          memory: JSON.parse(serialize(session.memory)),
          history: session.history,
        };
        writeFileSync(file, JSON.stringify(content, null, 2), "utf8");
        return sendJson(res, 200, { ok: true });
      }
      if (sub === "auto-gen-setting" && method === "POST") {
        try {
          const transport = options.transportFactory
            ? options.transportFactory(session)
            : defaultTransport(session, options.recordingsDir);
          const setting = await generateSetting(session.maid, transport);
          if (setting !== "") {
            session.maid.systemPrompt = setting;
          }
          return sendJson(res, 200, { setting });
        } catch (e) {
          return sendJson(res, 500, { error: (e as Error).message });
        }
      }
      if (sub === "auto-gen" && method === "PUT") {
        const b = await readBody(req);
        session.autoGenSetting = Boolean(b.enabled);
        return sendJson(res, 200, { autoGenSetting: session.autoGenSetting });
      }
      if (sub === "history" && method === "GET") {
        return sendJson(res, 200, session.history);
      }
      return sendJson(res, 404, { error: "not found", path: url.pathname });
    } catch (e) {
      if (e instanceof BodyTooLargeError) {
        return sendJson(res, 413, { error: e.message });
      }
      return sendJson(res, 500, { error: (e as Error).message });
    }
  });

  return {
    server,
    manager,
    start(): Promise<void> {
      return new Promise((r) => server.listen(options.port, options.host, () => r()));
    },
    stop(): Promise<void> {
      return new Promise((r) => server.close(() => r()));
    },
  };
}
