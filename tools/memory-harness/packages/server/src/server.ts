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
import { mkdir, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { resolve, sep } from "node:path";
import {
  ChatBusyError,
  loadSkills,
  SessionManager,
  type Session,
} from "./session.js";
import {
  SchemaError,
  validateConfig,
  validateEnv,
  validateScenarioBody,
} from "./schema.js";

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

function buildTransport(session: Session, recordingsDir: string): LLMTransport {
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

function defaultTransport(
  session: Session,
  recordingsDir: string,
  transportFactory?: (session: Session) => LLMTransport,
): LLMTransport {
  if (session.cachedTransport) {
    return session.cachedTransport;
  }
  const transport = transportFactory
    ? transportFactory(session)
    : buildTransport(session, recordingsDir);
  session.cachedTransport = transport;
  return transport;
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

async function listScenarios(scenariosDir: string) {
  if (!existsSync(scenariosDir)) {
    return [];
  }
  const files = await readdir(scenariosDir);
  const result: unknown[] = [];
  for (const f of files) {
    if (!f.endsWith(".json")) {
      continue;
    }
    try {
      const data = JSON.parse(
        await readFile(resolve(scenariosDir, f), "utf8"),
      ) as Record<string, unknown>;
      result.push({ id: data.id ?? f.replace(/\.json$/, ""), file: f, ...data });
    } catch {
      result.push({ file: f, error: "invalid" });
    }
  }
  return result;
}

async function listUserScenarios(dir: string): Promise<Array<{ name: string; savedAt: number }>> {
  if (!existsSync(dir)) {
    return [];
  }
  const files = await readdir(dir);
  const result: Array<{ name: string; savedAt: number }> = [];
  for (const f of files) {
    if (!f.endsWith(".json")) {
      continue;
    }
    try {
      const data = JSON.parse(
        await readFile(resolve(dir, f), "utf8"),
      ) as { name?: string; savedAt?: number };
      result.push({ name: data.name ?? f.replace(/\.json$/, ""), savedAt: data.savedAt ?? 0 });
    } catch {
      result.push({ name: f.replace(/\.json$/, ""), savedAt: 0 });
    }
  }
  return result;
}

async function listGameMemories(dir: string): Promise<Array<{ name: string; file: string }>> {
  if (!existsSync(dir)) {
    return [];
  }
  const files = await readdir(dir);
  return files
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
const LOOPBACK_REMOTE = new Set(["127.0.0.1", "::1", "::ffff:127.0.0.1"]);

function isLocalOrigin(req: IncomingMessage): boolean {
  const origin = req.headers.origin;
  if (origin === undefined || origin === "") {
    return false;
  }
  try {
    const u = new URL(origin);
    return LOCAL_HOSTS.has(u.hostname);
  } catch {
    return false;
  }
}

function getRemoteAddress(req: IncomingMessage): string | undefined {
  return (
    (req.socket as { remoteAddress?: string })?.remoteAddress ??
    (req.connection as { remoteAddress?: string })?.remoteAddress
  );
}

function isLocalRequest(req: IncomingMessage): boolean {
  if (isLocalOrigin(req)) {
    return true;
  }
  const remote = getRemoteAddress(req);
  if (remote && LOOPBACK_REMOTE.has(remote)) {
    return true;
  }
  return false;
}

function hasValidToken(req: IncomingMessage): boolean {
  const expected = process.env.SERVER_TOKEN;
  if (!expected) {
    return false;
  }
  const auth = req.headers.authorization || "";
  if (!auth.startsWith("Bearer ")) {
    return false;
  }
  return auth.slice(7) === expected;
}

function isLoopbackBinding(host: string): boolean {
  return LOOPBACK_REMOTE.has(host);
}

function isAuthorized(req: IncomingMessage): boolean {
  return isLocalRequest(req) || hasValidToken(req);
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

function sanitizeError(err: unknown): string {
  if (err instanceof SchemaError) {
    return err.message;
  }
  if (err instanceof ChatBusyError) {
    return "Session is busy with another chat request";
  }
  if (err instanceof BodyTooLargeError) {
    return err.message;
  }
  if (err instanceof Error) {
    return "Internal server error";
  }
  return "Internal server error";
}

function logError(context: string, err: unknown): void {
  if (err instanceof Error) {
    console.error(`[harness] ${context}: ${err.stack || err.message}`);
  } else {
    console.error(`[harness] ${context}: ${String(err)}`);
  }
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

  async function runChatStream(session: Session, message: string, req: IncomingMessage, res: ServerResponse): Promise<void> {
    await manager.runChat(session.id, async () => {
      const controller = new AbortController();
      const aborted = { value: false };

      const abort = (): void => {
        if (aborted.value) return;
        aborted.value = true;
        controller.abort();
      };
      const onRequestClose = (): void => {
        // IncomingMessage emits close after a normally completed request body.
        // Once the SSE response has started, req.close alone is therefore not
        // proof of a disconnect; res.close is authoritative for that phase.
        if (req.aborted || (!req.complete && !res.headersSent)) {
          abort();
        }
      };
      const onResponseClose = (): void => {
        // Normal res.end() marks writableEnded before close. A close before that
        // means the SSE peer has gone away.
        if (!res.writableEnded) {
          abort();
        }
      };
      req.once("close", onRequestClose);
      res.once("close", onResponseClose);

      try {
        res.writeHead(200, {
          "Content-Type": "text/event-stream",
          "Cache-Control": "no-cache",
          Connection: "keep-alive",
        });
        // Flush headers immediately so a client can establish/cancel the SSE
        // stream while a slow transport call is still pending.
        res.flushHeaders();
        const registry = buildRegistry();
        const ctx = {
          memory: session.memory,
          config: session.config,
          maid: session.maid,
          skills: session.skills,
          maintaining: false,
          save: () => manager.scheduleSaveMemory(session),
        };
        const transport = defaultTransport(
          session,
          options.recordingsDir,
          options.transportFactory,
        );
        const deps = {
          transport,
          registry,
          ctx,
          systemPrompt: session.maid.systemPrompt,
          loopConfig: DEFAULT_LOOP_CONFIG,
          signal: controller.signal,
          emit: (e: unknown): void => {
            if (aborted.value || res.writableEnded) return;
            const ev = e as { type: string };
            res.write(`event: ${ev.type}\ndata: ${JSON.stringify(e)}\n\n`);
          },
        };
        let outcome;
        try {
          outcome = await runAgentLoop(deps, session.history, message);
        } catch (e) {
          if (aborted.value) return;
          const msg = sanitizeError(e);
          logError("chat stream", e);
          if (!res.writableEnded) {
            res.write(`event: error\ndata: ${JSON.stringify({ type: "error", message: msg })}\n\n`);
            res.write(`event: done\ndata: ${JSON.stringify({ error: msg })}\n\n`);
            res.end();
          }
          return;
        }

        if (aborted.value) return;

        session.history = trimHistory([...session.history, ...outcome.newHistoryEntries], DEFAULT_LOOP_CONFIG.historyLimit);
        session.lastTrace = outcome.trace;
        session.tokenUsage.promptTokens += outcome.trace.promptTokens;
        session.tokenUsage.completionTokens += outcome.trace.completionTokens;
        session.tokenUsage.totalTokens += outcome.trace.promptTokens + outcome.trace.completionTokens;
        session.tokenUsage.chatCount += 1;
        session.lastActivityAt = Date.now();
        await manager.forceSaveMemory(session);

        if (!res.writableEnded) {
          res.write(`event: done\ndata: ${JSON.stringify({ finalText: outcome.finalText, error: outcome.error })}\n\n`);
          res.end();
        }
      } finally {
        req.removeListener("close", onRequestClose);
        res.removeListener("close", onResponseClose);
      }
    });
  }

  function applyScenario(session: Session, body: Record<string, unknown>): void {
    const env = body.env as Record<string, unknown> | undefined;
    const config = body.config as Record<string, unknown> | undefined;
    const nextMaid = env
      ? ({ ...session.maid, ...validateEnv(env) } as Session["maid"])
      : { ...session.maid };
    const nextConfig = config
      ? ({ ...session.config, ...validateConfig(config) } as Session["config"])
      : { ...session.config };
    const nextMemory = new MaidMemory(nextConfig.maxMemories);

    if (body.memory !== undefined) {
      for (const entry of body.memory as Array<Record<string, string>>) {
        const result = nextMemory.set(entry.key, entry.value, entry.importance);
        if (result === "rejected-empty" || result === "rejected-full") {
          throw new SchemaError("scenario memory entry is invalid", "memory");
        }
      }
    } else {
      for (const [key, entry] of session.memory.entries()) {
        nextMemory.putRaw(key, { ...entry });
      }
      nextMemory.setLastTidyAt(session.memory.getLastTidyAt());
    }

    if (nextMemory.size() > nextConfig.maxMemories) {
      throw new SchemaError(
        `scenario memory exceeds maxMemories (${nextConfig.maxMemories})`,
        "memory",
      );
    }

    const history = body.history as Session["history"] | undefined;
    const nextHistory = history ? structuredClone(history) : [...session.history];

    // All potentially failing work has completed. Commit state together so a
    // config maxMemories change cannot mutate the original memory on failure.
    session.maid = nextMaid;
    session.config = nextConfig;
    session.memory = nextMemory;
    session.history = nextHistory;
  }

  const server = httpCreateServer(async (req: IncomingMessage, res: ServerResponse) => {
    const url = new URL(req.url ?? "/", "http://localhost");
    const seg = url.pathname.split("/").filter(Boolean);
    const method = req.method ?? "GET";

    try {
      if (!isLoopbackBinding(options.host) && !isAuthorized(req)) {
        return sendJson(res, 403, { error: "forbidden: authentication required for non-loopback binding" });
      }

      if (isWriteMethod(method) && !isLocalRequest(req) && !hasValidToken(req)) {
        return sendJson(res, 403, { error: "forbidden: non-local origin" });
      }

      if (seg[0] !== "api") {
        return sendJson(res, 404, { error: "not found", path: url.pathname });
      }
      if (seg[1] === "scenarios" && method === "GET") {
        return sendJson(res, 200, await listScenarios(scenariosDir));
      }
      if (seg[1] === "user-scenarios") {
        if (seg.length === 2 && method === "GET") {
          return sendJson(res, 200, await listUserScenarios(userScenariosDir));
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
            return sendJson(res, 200, JSON.parse(await readFile(file, "utf8")));
          }
          if (method === "DELETE") {
            if (existsSync(file)) {
              await rm(file, { force: true });
            }
            return sendJson(res, 200, { ok: true });
          }
        }
        return sendJson(res, 404, { error: "not found", path: url.pathname });
      }
      if (seg[1] === "game-memories") {
        if (seg.length === 2 && method === "GET") {
          return sendJson(res, 200, await listGameMemories(gameMemoriesDir));
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
          return sendJson(res, 200, { name, content: await readFile(file, "utf8") });
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
      session.lastActivityAt = Date.now();
      const sub = seg[3];
      if (sub === "chat" && method === "POST") {
        const body = await readBody(req);
        return await runChatStream(session, String(body.message ?? ""), req, res);
      }
      if (sub === "summarize" && method === "POST") {
        return await runChatStream(session, SUMMARIZE_PROMPT, req, res);
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
          await manager.forceSaveMemory(session);
          return sendJson(res, 200, { ok: true, result });
        }
        if (seg.length === 5 && method === "DELETE") {
          session.memory.forget(decodeURIComponent(seg[4]));
          await manager.forceSaveMemory(session);
          return sendJson(res, 200, { ok: true });
        }
        if (seg.length === 5 && seg[4] === "import" && method === "POST") {
          const b = await readBody(req);
          const json = typeof b.json === "string" ? b.json : JSON.stringify(b.json ?? b);
          session.memory = deserialize(json, session.config.maxMemories);
          await manager.forceSaveMemory(session);
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
          const validated = validateEnv(b);
          session.maid = { ...session.maid, ...validated } as Session["maid"];
          session.lastActivityAt = Date.now();
          return sendJson(res, 200, session.maid);
        }
      }
      if (sub === "config") {
        if (method === "GET") {
          return sendJson(res, 200, session.config);
        }
        if (method === "PATCH") {
          const b = await readBody(req);
          const validated = validateConfig(b);
          const nextConfig = { ...session.config, ...validated } as Session["config"];
          if (session.memory.size() > nextConfig.maxMemories) {
            throw new SchemaError(
              `existing memory exceeds maxMemories (${nextConfig.maxMemories})`,
              "maxMemories",
            );
          }
          if (nextConfig.maxMemories !== session.config.maxMemories) {
            session.memory.setMaxMemories(nextConfig.maxMemories);
          }
          session.config = nextConfig;
          session.lastActivityAt = Date.now();
          return sendJson(res, 200, session.config);
        }
      }
      if (sub === "trace" && method === "GET") {
        return sendJson(res, 200, session.lastTrace ?? { messages: [], toolLogs: [] });
      }
      if (sub === "load-scenario" && method === "POST") {
        const b = await readBody(req);
        validateScenarioBody(b);
        applyScenario(session, b);
        session.lastActivityAt = Date.now();
        await manager.forceSaveMemory(session);
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
        await mkdir(userScenariosDir, { recursive: true });
        const content = {
          name,
          savedAt: Date.now(),
          env: { ...session.maid },
          config: { ...session.config },
          memory: JSON.parse(serialize(session.memory)),
          history: session.history,
        };
        await writeFile(file, JSON.stringify(content, null, 2), "utf8");
        return sendJson(res, 200, { ok: true });
      }
      if (sub === "auto-gen-setting" && method === "POST") {
        try {
          const transport = options.transportFactory
            ? options.transportFactory(session)
            : buildTransport(session, options.recordingsDir);
          const setting = await generateSetting(session.maid, transport);
          if (setting !== "") {
            session.maid.systemPrompt = setting;
          }
          return sendJson(res, 200, { setting });
        } catch (e) {
          const msg = sanitizeError(e);
          logError("auto-gen-setting", e);
          return sendJson(res, 500, { error: msg });
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
      if (e instanceof ChatBusyError) {
        return sendJson(res, 409, { error: "Session is busy with another chat request" });
      }
      if (e instanceof SchemaError) {
        return sendJson(res, 400, { error: e.message });
      }
      const msg = sanitizeError(e);
      logError("request handler", e);
      return sendJson(res, 500, { error: msg });
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
