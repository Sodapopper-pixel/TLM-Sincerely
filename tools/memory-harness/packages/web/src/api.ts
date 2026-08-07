/** HTTP + SSE client for memory harness backend (/api proxied to :7421). */

export interface TokenUsageDto {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  chatCount: number;
}

export interface SessionSummary {
  id: string;
  maidName: string;
  memoryCount: number;
  config: MemoryConfigDto;
  historyLength: number;
  tokenUsage: TokenUsageDto;
  autoGenSetting: boolean;
}

export interface MemoryConfigDto {
  enabled: boolean;
  maxMemories: number;
  coreLimit: number;
  contextPreviewLength: number;
}

export interface MemoryEntryDto {
  value: string;
  importance: "core" | "archive" | string;
  createdAt: number;
  updatedAt: number;
}

export interface MemoryMapDto {
  memories: Record<string, MemoryEntryDto>;
}

export interface VirtualMaidDto {
  uuid: string;
  name: string;
  language: string;
  systemPrompt: string;
  health: number;
  maxHealth: number;
  isSleeping: boolean;
  isFollowing: boolean;
  sitting: boolean;
  schedule: string;
  activity: string;
  task: string;
  timeOfDay: number;
  weather: string;
  dimension: string;
  biome: string;
  userName: string;
  userHealth: number;
  riding?: string;
  userMainHand?: string;
  equipment?: Record<string, string>;
  effects?: string[];
  position?: { x: number; y: number; z: number };
  nearby?: Array<{ type: string; name: string; distance: number }>;
  [key: string]: unknown;
}

export interface MemoryDiff {
  added: string[];
  updated: string[];
  removed: string[];
}

export interface ToolLogEntry {
  id: string;
  name: string;
  arguments: string;
  result: string;
}

export interface SkillSummaryDto {
  name: string;
  description: string;
}

export interface TraceData {
  messages: unknown[];
  toolLogs: ToolLogEntry[];
  tools?: unknown[];
  skills?: SkillSummaryDto[];
  promptTokens?: number;
  completionTokens?: number;
}

export type SseEvent =
  | { type: "context"; context: string }
  | { type: "tool_call"; id: string; name: string; arguments: string }
  | { type: "tool_result"; id: string; content: string }
  | { type: "memory_diff"; diff: MemoryDiff }
  | { type: "final"; text: string }
  | { type: "error"; message: string }
  | { type: "trace_ready"; trace: TraceData }
  | { type: "done"; finalText?: string; error?: string }
  | { type: string; [key: string]: unknown };

export interface ScenarioDto {
  id?: string;
  file?: string;
  intent?: string;
  env?: Record<string, unknown>;
  initialMemory?: Array<{ key: string; value: string; importance?: string }>;
  memory?: Array<{ key: string; value: string; importance?: string }>;
  history?: unknown[];
  opening?: string;
  error?: string;
  [key: string]: unknown;
}

export interface UserScenarioSummaryDto {
  name: string;
  savedAt: number;
}

export interface UserScenarioDto {
  name: string;
  savedAt: number;
  env: Record<string, unknown>;
  config: Record<string, unknown>;
  memory: unknown;
  history: unknown[];
}

export interface LLMMessageToolCallDto {
  id: string;
  type: "function";
  function: { name: string; arguments: string };
}

export interface LLMMessageDto {
  role: string;
  content: string | null;
  tool_calls?: LLMMessageToolCallDto[];
  tool_call_id?: string;
  name?: string;
}

export interface GameMemorySummaryDto {
  name: string;
  file: string;
}

export interface GameMemoryDto {
  name: string;
  content: string;
}

export type ExportFormat = "json" | "text" | "context";

async function parseJson<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let detail = res.statusText;
    try {
      const body = (await res.json()) as { error?: string };
      if (body.error) {
        detail = body.error;
      }
    } catch {
      /* ignore */
    }
    throw new Error(`HTTP ${res.status}: ${detail}`);
  }
  return (await res.json()) as T;
}

export async function listSessions(): Promise<SessionSummary[]> {
  return parseJson(await fetch("/api/sessions"));
}

export async function createSession(): Promise<SessionSummary> {
  return parseJson(
    await fetch("/api/sessions", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    }),
  );
}

export async function getSession(id: string): Promise<SessionSummary> {
  return parseJson(await fetch(`/api/sessions/${encodeURIComponent(id)}`));
}

export async function deleteSession(id: string): Promise<void> {
  await parseJson(await fetch(`/api/sessions/${encodeURIComponent(id)}`, { method: "DELETE" }));
}

export async function getMemory(id: string): Promise<MemoryMapDto> {
  return parseJson(await fetch(`/api/sessions/${encodeURIComponent(id)}/memory`));
}

export async function putMemory(
  id: string,
  body: { key: string; value: string; importance: string },
): Promise<void> {
  await parseJson(
    await fetch(`/api/sessions/${encodeURIComponent(id)}/memory`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }),
  );
}

export async function deleteMemory(id: string, key: string): Promise<void> {
  await parseJson(
    await fetch(
      `/api/sessions/${encodeURIComponent(id)}/memory/${encodeURIComponent(key)}`,
      { method: "DELETE" },
    ),
  );
}

export async function importMemory(id: string, json: string): Promise<void> {
  await parseJson(
    await fetch(`/api/sessions/${encodeURIComponent(id)}/memory/import`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ json }),
    }),
  );
}

export async function exportMemory(
  id: string,
  format: ExportFormat,
): Promise<{ format: string; content: string }> {
  return parseJson(
    await fetch(
      `/api/sessions/${encodeURIComponent(id)}/memory/export?format=${encodeURIComponent(format)}`,
    ),
  );
}

export async function getEnv(id: string): Promise<VirtualMaidDto> {
  return parseJson(await fetch(`/api/sessions/${encodeURIComponent(id)}/env`));
}

export async function patchEnv(
  id: string,
  patch: Partial<VirtualMaidDto>,
): Promise<VirtualMaidDto> {
  return parseJson(
    await fetch(`/api/sessions/${encodeURIComponent(id)}/env`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(patch),
    }),
  );
}

export async function getConfig(id: string): Promise<MemoryConfigDto> {
  return parseJson(await fetch(`/api/sessions/${encodeURIComponent(id)}/config`));
}

export async function patchConfig(
  id: string,
  patch: Partial<MemoryConfigDto>,
): Promise<MemoryConfigDto> {
  return parseJson(
    await fetch(`/api/sessions/${encodeURIComponent(id)}/config`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(patch),
    }),
  );
}

export async function getTrace(id: string): Promise<TraceData> {
  return parseJson(await fetch(`/api/sessions/${encodeURIComponent(id)}/trace`));
}

export async function listScenarios(): Promise<ScenarioDto[]> {
  return parseJson(await fetch("/api/scenarios"));
}

export async function loadScenario(
  id: string,
  body: {
    env?: Record<string, unknown>;
    memory?: Array<Record<string, string>> | unknown;
    history?: unknown[];
    config?: Record<string, unknown>;
  },
): Promise<void> {
  await parseJson(
    await fetch(`/api/sessions/${encodeURIComponent(id)}/load-scenario`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }),
  );
}

export async function listUserScenarios(): Promise<UserScenarioSummaryDto[]> {
  return parseJson(await fetch("/api/user-scenarios"));
}

export async function getUserScenario(name: string): Promise<UserScenarioDto> {
  return parseJson(await fetch(`/api/user-scenarios/${encodeURIComponent(name)}`));
}

export async function deleteUserScenario(name: string): Promise<void> {
  await parseJson(await fetch(`/api/user-scenarios/${encodeURIComponent(name)}`, { method: "DELETE" }));
}

export async function saveUserScenario(sessionId: string, name: string): Promise<void> {
  await parseJson(
    await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/save-scenario`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name }),
    }),
  );
}

export async function autoGenSetting(sessionId: string): Promise<{ setting: string }> {
  return parseJson(
    await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/auto-gen-setting`, { method: "POST" }),
  );
}

export async function setAutoGenSetting(
  sessionId: string,
  enabled: boolean,
): Promise<{ autoGenSetting: boolean }> {
  return parseJson(
    await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/auto-gen`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ enabled }),
    }),
  );
}

export async function getHistory(sessionId: string): Promise<LLMMessageDto[]> {
  return parseJson(await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/history`));
}

export async function listGameMemories(): Promise<GameMemorySummaryDto[]> {
  return parseJson(await fetch("/api/game-memories"));
}

export async function getGameMemory(name: string): Promise<GameMemoryDto> {
  return parseJson(await fetch(`/api/game-memories/${encodeURIComponent(name)}`));
}

/**
 * Parse SSE from a ReadableStream (POST responses).
 * Lines: `event: <type>`, `data: <JSON>`, blank line dispatches.
 */
export async function streamSse(
  url: string,
  body: unknown,
  onEvent: (event: SseEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const init: RequestInit = {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "text/event-stream",
    },
    body: JSON.stringify(body),
  };
  if (signal !== undefined) {
    init.signal = signal;
  }
  const res = await fetch(url, init);

  if (!res.ok) {
    let detail = res.statusText;
    try {
      const errBody = (await res.json()) as { error?: string };
      if (errBody.error) {
        detail = errBody.error;
      }
    } catch {
      /* ignore */
    }
    throw new Error(`HTTP ${res.status}: ${detail}`);
  }

  if (!res.body) {
    throw new Error("Response body is null (no stream)");
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  let eventType = "";
  let dataLines: string[] = [];

  const flush = (): void => {
    if (dataLines.length === 0 && eventType === "") {
      return;
    }
    const raw = dataLines.join("\n");
    dataLines = [];
    const type = eventType || "message";
    eventType = "";
    if (raw === "") {
      return;
    }
    try {
      const parsed = JSON.parse(raw) as Record<string, unknown>;
      const evType = typeof parsed.type === "string" ? parsed.type : type;
      onEvent({ ...parsed, type: evType } as SseEvent);
    } catch {
      onEvent({ type, raw } as SseEvent);
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split(/\r?\n/);
    buffer = lines.pop() ?? "";

    for (const line of lines) {
      if (line === "") {
        flush();
        continue;
      }
      if (line.startsWith(":")) {
        continue;
      }
      if (line.startsWith("event:")) {
        eventType = line.slice(6).trim();
        continue;
      }
      if (line.startsWith("data:")) {
        dataLines.push(line.slice(5).replace(/^ /, ""));
        continue;
      }
    }
  }

  if (buffer.trim() !== "") {
    const leftover = buffer.split(/\r?\n/);
    for (const line of leftover) {
      if (line === "") {
        flush();
      } else if (line.startsWith("event:")) {
        eventType = line.slice(6).trim();
      } else if (line.startsWith("data:")) {
        dataLines.push(line.slice(5).replace(/^ /, ""));
      }
    }
  }
  flush();
}

export function streamChat(
  sessionId: string,
  message: string,
  onEvent: (event: SseEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  return streamSse(
    `/api/sessions/${encodeURIComponent(sessionId)}/chat`,
    { message },
    onEvent,
    signal,
  );
}

export function streamSummarize(
  sessionId: string,
  onEvent: (event: SseEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  return streamSse(
    `/api/sessions/${encodeURIComponent(sessionId)}/summarize`,
    {},
    onEvent,
    signal,
  );
}

export function toolCallSummary(name: string, argsJson: string): string {
  try {
    const args = JSON.parse(argsJson) as Record<string, unknown>;
    const parts: string[] = [name];
    if (typeof args.action === "string") {
      parts.push(args.action);
    }
    if (typeof args.key === "string" && args.key !== "") {
      parts.push(args.key);
    } else if (typeof args.name === "string" && args.name !== "") {
      parts.push(args.name);
    }
    return parts.join(" ");
  } catch {
    return name;
  }
}

export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    try {
      const ta = document.createElement("textarea");
      ta.value = text;
      ta.style.position = "fixed";
      ta.style.left = "-9999px";
      document.body.appendChild(ta);
      ta.select();
      const ok = document.execCommand("copy");
      document.body.removeChild(ta);
      return ok;
    } catch {
      return false;
    }
  }
}
