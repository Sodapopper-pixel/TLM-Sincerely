import type { LLMMessage, LLMTransport, ToolCall, ToolSchema } from "./types.js";
import { buildContextBlock, buildUserMessage } from "./context-builder.js";
import type { ToolContext, ToolRegistry, ToolResult, MemoryDiff } from "./tool-registry.js";

export interface AgentLoopConfig {
  maxToolTurns: number;
  maxRepeatBatch: number;
  historyLimit: number;
  temperature: number;
}

export const DEFAULT_LOOP_CONFIG: AgentLoopConfig = {
  maxToolTurns: 16,
  maxRepeatBatch: 2,
  historyLimit: 24,
  temperature: 0,
};

export interface ToolLogEntry {
  id: string;
  name: string;
  arguments: string;
  result: string;
}

export interface SkillSummary {
  name: string;
  description: string;
}

export interface TokenUsage {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  chatCount: number;
}

export function emptyTokenUsage(): TokenUsage {
  return { promptTokens: 0, completionTokens: 0, totalTokens: 0, chatCount: 0 };
}

export interface TraceData {
  messages: LLMMessage[];
  toolLogs: ToolLogEntry[];
  tools: ToolSchema[];
  skills: SkillSummary[];
  promptTokens: number;
  completionTokens: number;
}

export type AgentLoopEvent =
  | { type: "context"; context: string }
  | { type: "tool_call"; id: string; name: string; arguments: string }
  | { type: "tool_result"; id: string; content: string }
  | { type: "memory_diff"; diff: MemoryDiff }
  | { type: "final"; text: string }
  | { type: "error"; message: string }
  | { type: "trace_ready"; trace: TraceData };

export interface AgentLoopDeps {
  transport: LLMTransport;
  registry: ToolRegistry;
  ctx: ToolContext;
  systemPrompt: string;
  loopConfig: AgentLoopConfig;
  emit: (event: AgentLoopEvent) => void;
}

export interface AgentLoopOutcome {
  finalText: string;
  error: string | undefined;
  trace: TraceData;
  newHistoryEntries: LLMMessage[];
}

function deleteWhitespace(s: string): string {
  return s.replace(/\s+/g, "");
}

function callSignature(call: ToolCall): string {
  const name = call.function.name || "unknown";
  return `${name}|${deleteWhitespace(call.function.arguments || "")}`;
}

function dedupCalls(calls: ToolCall[]): ToolCall[] {
  const seen = new Set<string>();
  const out: ToolCall[] = [];
  for (const c of calls) {
    const sig = callSignature(c);
    if (!seen.has(sig)) {
      seen.add(sig);
      out.push(c);
    }
  }
  return out;
}

function batchSignature(calls: ToolCall[]): string {
  return calls.map(callSignature).join("||");
}

function executeToolCall(call: ToolCall, deps: AgentLoopDeps): ToolResult {
  const tool = deps.registry.get(call.function.name);
  if (!tool) {
    return {
      content: `Unknown tool '${call.function.name}'. It is not registered.\nUse only tool ids from the provided schema and retry.\n`,
    };
  }
  let args: Record<string, unknown>;
  try {
    args = call.function.arguments ? (JSON.parse(call.function.arguments) as Record<string, unknown>) : {};
  } catch {
    return { content: `Failed to parse arguments for '${call.function.name}'.` };
  }
  try {
    return tool.execute(args, deps.ctx);
  } catch (e) {
    return { content: `Tool '${call.function.name}' error: ${(e as Error).message}` };
  }
}

export async function runAgentLoop(
  deps: AgentLoopDeps,
  history: LLMMessage[],
  userText: string,
): Promise<AgentLoopOutcome> {
  const { maid, memory, config } = deps.ctx;
  const userMessage: LLMMessage = {
    role: "user",
    content: buildUserMessage(maid, memory, config, userText),
  };
  deps.emit({ type: "context", context: buildContextBlock(maid, memory, config) });

  const messages: LLMMessage[] =
    deps.systemPrompt.trim() !== ""
      ? [{ role: "system", content: deps.systemPrompt }, ...history, userMessage]
      : [...history, userMessage];
  const toolLogs: ToolLogEntry[] = [];
  const tools: ToolSchema[] = deps.registry.schemas();
  let promptTokens = 0;
  let completionTokens = 0;

  let turnCount = 0;
  let lastBatchSig: string | null = null;
  let repeatCount = 0;
  let finalText = "";
  let error: string | undefined;

  while (true) {
    const resp = await deps.transport.chat(messages, tools, { temperature: deps.loopConfig.temperature });
    if (resp.usage) {
      promptTokens += resp.usage.prompt_tokens ?? 0;
      completionTokens += resp.usage.completion_tokens ?? 0;
    }
    const calls = resp.tool_calls ?? [];
    if (calls.length === 0) {
      finalText = resp.content ?? "";
      messages.push({ role: "assistant", content: finalText });
      deps.emit({ type: "final", text: finalText });
      break;
    }

    const deduped = dedupCalls(calls);
    turnCount++;
    if (turnCount > deps.loopConfig.maxToolTurns) {
      error = `Tool turn count exceed max count: ${deps.loopConfig.maxToolTurns}`;
      deps.emit({ type: "error", message: error });
      break;
    }
    const batchSig = batchSignature(deduped);
    if (batchSig === lastBatchSig) {
      repeatCount++;
    } else {
      repeatCount = 1;
      lastBatchSig = batchSig;
    }
    if (repeatCount > deps.loopConfig.maxRepeatBatch) {
      error = `Repeated identical tool batch exceed max count: ${batchSig}`;
      deps.emit({ type: "error", message: error });
      break;
    }

    messages.push({ role: "assistant", content: resp.content, tool_calls: deduped });
    for (const call of deduped) {
      deps.emit({ type: "tool_call", id: call.id, name: call.function.name, arguments: call.function.arguments });
      const result = executeToolCall(call, deps);
      messages.push({
        role: "tool",
        content: result.content,
        tool_call_id: call.id,
        name: call.function.name,
      });
      toolLogs.push({ id: call.id, name: call.function.name, arguments: call.function.arguments, result: result.content });
      deps.emit({ type: "tool_result", id: call.id, content: result.content });
      if (result.memoryDiff) {
        deps.emit({ type: "memory_diff", diff: result.memoryDiff });
      }
    }
  }

  const userIdx = (deps.systemPrompt.trim() !== "" ? 1 : 0) + history.length;
  const turnsAfter = messages.slice(userIdx + 1);
  const newHistoryEntries: LLMMessage[] = [{ role: "user", content: userText }, ...turnsAfter];

  const skillsSummary: SkillSummary[] = deps.ctx.skills.map((s) => ({
    name: s.name,
    description: s.description,
  }));
  const trace: TraceData = { messages, toolLogs, tools, skills: skillsSummary, promptTokens, completionTokens };
  deps.emit({ type: "trace_ready", trace });
  return { finalText, error, trace, newHistoryEntries };
}

export function trimHistory(history: LLMMessage[], limit: number): LLMMessage[] {
  if (history.length <= limit) {
    return history;
  }
  let start = history.length - limit;
  // Never begin the trimmed history with an orphaned tool-role message (its
  // matching assistant tool_call would be cut off, producing a malformed prefix
  // that OpenAI-compatible endpoints reject with 400). Advance past any leading
  // tool messages until we hit a user/assistant/system message.
  while (start < history.length && history[start].role === "tool") {
    start++;
  }
  return history.slice(start);
}
