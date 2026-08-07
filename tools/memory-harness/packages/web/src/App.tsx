import { useCallback, useEffect, useRef, useState } from "react";
import {
  copyText,
  createSession,
  autoGenSetting,
  deleteMemory,
  deleteUserScenario,
  deleteSession,
  exportMemory,
  getGameMemory,
  getConfig,
  getEnv,
  getHistory,
  getMemory,
  getTrace,
  getUserScenario,
  importMemory,
  listGameMemories,
  listScenarios,
  listSessions,
  listUserScenarios,
  loadScenario,
  patchConfig,
  patchEnv,
  putMemory,
  saveUserScenario,
  setAutoGenSetting,
  streamChat,
  streamSummarize,
  type ExportFormat,
  type GameMemorySummaryDto,
  type LLMMessageDto,
  type MemoryConfigDto,
  type MemoryMapDto,
  type ScenarioDto,
  type SessionSummary,
  type SseEvent,
  type TokenUsageDto,
  type TraceData,
  type UserScenarioSummaryDto,
  type VirtualMaidDto,
} from "./api";
import { Chat, type ChatItem } from "./components/Chat";
import { DebugPanel } from "./components/DebugPanel";
import {
  MemoryInspector,
  type DiffHighlight,
} from "./components/MemoryInspector";
import { Sidebar } from "./components/Sidebar";

function uid(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

type ToolChatItem = Extract<ChatItem, { kind: "tool" }>;

function rebuildChatItems(history: LLMMessageDto[]): ChatItem[] {
  const items: ChatItem[] = [];
  const toolById = new Map<string, ToolChatItem>();
  for (const m of history) {
    if (m.role === "user") {
      items.push({ kind: "user", id: uid(), text: m.content ?? "" });
    } else if (m.role === "assistant") {
      if (Array.isArray(m.tool_calls)) {
        for (const c of m.tool_calls) {
          const it: ToolChatItem = {
            kind: "tool",
            id: uid(),
            toolId: c.id,
            name: c.function.name,
            arguments: c.function.arguments,
          };
          toolById.set(c.id, it);
          items.push(it);
        }
      }
      if (m.content !== null && m.content.trim() !== "") {
        items.push({ kind: "assistant", id: uid(), text: m.content });
      }
    } else if (m.role === "tool") {
      const it = toolById.get(m.tool_call_id ?? "");
      if (it) {
        it.result = m.content ?? "";
      }
    }
  }
  return items;
}

const GLOBAL_TOKENS_KEY = "tlm-harness:global-tokens";
const SESSION_ID_KEY = "tlm-harness:session-id";

function loadGlobalTokens(): TokenUsageDto {
  try {
    const raw = localStorage.getItem(GLOBAL_TOKENS_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<TokenUsageDto>;
      if (
        typeof parsed.promptTokens === "number" &&
        typeof parsed.completionTokens === "number" &&
        typeof parsed.totalTokens === "number" &&
        typeof parsed.chatCount === "number"
      ) {
        return parsed as TokenUsageDto;
      }
    }
  } catch {
    /* ignore */
  }
  return { promptTokens: 0, completionTokens: 0, totalTokens: 0, chatCount: 0 };
}

const NEXT_ROUND_HINT = "将影响下一轮 context（不自动发聊天）";

export function App(): JSX.Element {
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [scenarios, setScenarios] = useState<ScenarioDto[]>([]);
  const [selectedScenarioId, setSelectedScenarioId] = useState("");
  const [env, setEnv] = useState<VirtualMaidDto | null>(null);
  const [config, setConfig] = useState<MemoryConfigDto | null>(null);
  const [memories, setMemories] = useState<MemoryMapDto | null>(null);
  const [highlights, setHighlights] = useState<Record<string, DiffHighlight>>({});
  const [chatItems, setChatItems] = useState<ChatItem[]>([]);
  const [draft, setDraft] = useState("");
  const [streaming, setStreaming] = useState(false);
  const [trace, setTrace] = useState<TraceData | null>(null);
  const [lastContext, setLastContext] = useState<string | null>(null);
  const [nextRoundHint, setNextRoundHint] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [memStatus, setMemStatus] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [globalTokens, setGlobalTokens] = useState<TokenUsageDto>(loadGlobalTokens);
  const [userScenarios, setUserScenarios] = useState<UserScenarioSummaryDto[]>([]);
  const [newScenarioName, setNewScenarioName] = useState("");
  const [autoGenEnabled, setAutoGenEnabled] = useState(true);
  const [gameMemories, setGameMemories] = useState<GameMemorySummaryDto[]>([]);

  const abortRef = useRef<AbortController | null>(null);
  const envTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const cfgTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const sessionIdRef = useRef<string | null>(null);
  sessionIdRef.current = sessionId;

  const refreshSessions = useCallback(async (): Promise<SessionSummary[]> => {
    const list = await listSessions();
    setSessions(list);
    return list;
  }, []);

  const loadSessionData = useCallback(async (id: string): Promise<void> => {
    const [e, c, m, t, h] = await Promise.all([
      getEnv(id),
      getConfig(id),
      getMemory(id),
      getTrace(id),
      getHistory(id),
    ]);
    if (sessionIdRef.current !== id) {
      return;
    }
    setEnv(e);
    setConfig(c);
    setMemories(m);
    setTrace(t);
    setChatItems(rebuildChatItems(h));
    setHighlights({});
    setNextRoundHint(null);
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        setBusy(true);
        const [list, sc, us, gm] = await Promise.all([listSessions(), listScenarios(), listUserScenarios(), listGameMemories()]);
        if (cancelled) {
          return;
        }
        setSessions(list);
        setScenarios(sc);
        setUserScenarios(us);
        setGameMemories(gm);
        let saved: string | null = null;
        try {
          saved = localStorage.getItem(SESSION_ID_KEY);
        } catch {
          /* ignore */
        }
        const target = (saved !== null ? list.find((x) => x.id === saved) : undefined) ?? list[0];
        if (target) {
          setSessionId(target.id);
          await loadSessionData(target.id);
        }
      } catch (e) {
        if (!cancelled) {
          setError((e as Error).message);
        }
      } finally {
        if (!cancelled) {
          setBusy(false);
        }
      }
    })();
    return () => {
      cancelled = true;
      abortRef.current?.abort();
      if (envTimer.current) {
        clearTimeout(envTimer.current);
      }
      if (cfgTimer.current) {
        clearTimeout(cfgTimer.current);
      }
    };
  }, [loadSessionData]);

  useEffect(() => {
    const s = sessions.find((x) => x.id === sessionId);
    if (s) {
      setAutoGenEnabled(s.autoGenSetting);
    }
  }, [sessionId, sessions]);

  useEffect(() => {
    if (sessionId !== null) {
      try {
        localStorage.setItem(SESSION_ID_KEY, sessionId);
      } catch {
        /* ignore */
      }
    }
  }, [sessionId]);

  const selectSession = async (id: string): Promise<void> => {
    if (id === sessionId) {
      return;
    }
    abortRef.current?.abort();
    setStreaming(false);
    setSessionId(id);
    setChatItems([]);
    setDraft("");
    setLastContext(null);
    setError(null);
    try {
      setBusy(true);
      await loadSessionData(id);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const handleCreateSession = async (): Promise<void> => {
    try {
      setBusy(true);
      setError(null);
      const s = await createSession();
      await refreshSessions();
      setSessionId(s.id);
      setChatItems([]);
      setDraft("");
      setLastContext(null);
      await loadSessionData(s.id);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const handleDeleteSession = async (id: string): Promise<void> => {
    try {
      setBusy(true);
      setError(null);
      await deleteSession(id);
      const list = await refreshSessions();
      if (sessionId === id) {
        abortRef.current?.abort();
        setStreaming(false);
        if (list.length > 0) {
          setSessionId(list[0].id);
          setChatItems([]);
          await loadSessionData(list[0].id);
        } else {
          setSessionId(null);
          setEnv(null);
          setConfig(null);
          setMemories(null);
          setTrace(null);
          setChatItems([]);
        }
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const handleEnvChange = (patch: Partial<VirtualMaidDto>): void => {
    if (sessionId === null) {
      return;
    }
    setEnv((prev) => (prev ? { ...prev, ...patch } : prev));
    setNextRoundHint(NEXT_ROUND_HINT);
    if (envTimer.current) {
      clearTimeout(envTimer.current);
    }
    const id = sessionId;
    envTimer.current = setTimeout(() => {
      void (async () => {
        try {
          const updated = await patchEnv(id, patch);
          if (sessionIdRef.current === id) {
            setEnv(updated);
          }
        } catch (e) {
          setError((e as Error).message);
        }
      })();
    }, 300);
  };

  const handleConfigChange = (patch: Partial<MemoryConfigDto>): void => {
    if (sessionId === null) {
      return;
    }
    setConfig((prev) => (prev ? { ...prev, ...patch } : prev));
    setNextRoundHint(NEXT_ROUND_HINT);
    if (cfgTimer.current) {
      clearTimeout(cfgTimer.current);
    }
    const id = sessionId;
    cfgTimer.current = setTimeout(() => {
      void (async () => {
        try {
          const updated = await patchConfig(id, patch);
          if (sessionIdRef.current === id) {
            setConfig(updated);
          }
        } catch (e) {
          setError((e as Error).message);
        }
      })();
    }, 300);
  };

  const handleLoadScenario = async (): Promise<void> => {
    if (sessionId === null || selectedScenarioId === "") {
      return;
    }
    const sc = scenarios.find(
      (s) => String(s.id ?? s.file ?? "") === selectedScenarioId,
    );
    if (!sc) {
      setError(`场景未找到: ${selectedScenarioId}`);
      return;
    }
    try {
      setBusy(true);
      setError(null);
      const memSource = sc.memory ?? sc.initialMemory ?? [];
      const body: {
        env?: Record<string, unknown>;
        memory?: Array<Record<string, string>>;
        history?: unknown[];
      } = {
        history: Array.isArray(sc.history) ? sc.history : [],
      };
      if (sc.env && typeof sc.env === "object") {
        body.env = sc.env as Record<string, unknown>;
      }
      if (Array.isArray(memSource)) {
        body.memory = memSource.map((e) => ({
          key: String(e.key ?? ""),
          value: String(e.value ?? ""),
          importance: String(e.importance ?? "archive"),
        }));
      }
      await loadScenario(sessionId, body);
      setChatItems([]);
      setLastContext(null);
      setHighlights({});
      await loadSessionData(sessionId);
      await refreshSessions();
      setMemStatus(`已加载场景 ${selectedScenarioId}`);
      if (typeof sc.opening === "string" && sc.opening !== "") {
        setDraft(sc.opening);
      }
      setNextRoundHint(NEXT_ROUND_HINT);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const handleSaveScenario = async (): Promise<void> => {
    if (sessionId === null || newScenarioName.trim() === "") {
      return;
    }
    try {
      setBusy(true);
      setError(null);
      const name = newScenarioName.trim();
      await saveUserScenario(sessionId, name);
      setUserScenarios(await listUserScenarios());
      setMemStatus(`已保存场景 ${name}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const handleLoadUserScenario = async (name: string): Promise<void> => {
    try {
      setBusy(true);
      setError(null);
      const sc = await getUserScenario(name);
      const ns = await createSession();
      await refreshSessions();
      await loadScenario(ns.id, {
        env: sc.env,
        memory: sc.memory,
        history: sc.history,
        config: sc.config,
      });
      await refreshSessions();
      setSessionId(ns.id);
      setChatItems([]);
      setLastContext(null);
      setHighlights({});
      await loadSessionData(ns.id);
      setMemStatus(`已加载用户场景 ${name}`);
      setNextRoundHint(NEXT_ROUND_HINT);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const handleDeleteUserScenario = async (name: string): Promise<void> => {
    try {
      setBusy(true);
      setError(null);
      await deleteUserScenario(name);
      setUserScenarios(await listUserScenarios());
      setMemStatus(`已删除场景 ${name}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const refreshMemory = async (id: string): Promise<void> => {
    const m = await getMemory(id);
    if (sessionIdRef.current === id) {
      setMemories(m);
    }
  };

  const applySseEvent = (
    ev: SseEvent,
    state: {
      assistantId: string | null;
      toolMap: Map<string, string>;
    },
  ): void => {
    switch (ev.type) {
      case "context": {
        const ctx = typeof ev.context === "string" ? ev.context : "";
        setLastContext(ctx);
        break;
      }
      case "tool_call": {
        const toolId = String(ev.id ?? "");
        const name = String(ev.name ?? "unknown");
        const args = String(ev.arguments ?? "");
        const itemId = uid();
        state.toolMap.set(toolId, itemId);
        setChatItems((prev) => [
          ...prev,
          {
            kind: "tool",
            id: itemId,
            toolId,
            name,
            arguments: args,
          },
        ]);
        break;
      }
      case "tool_result": {
        const toolId = String(ev.id ?? "");
        const content = String(ev.content ?? "");
        const itemId = state.toolMap.get(toolId);
        setChatItems((prev) =>
          prev.map((it) =>
            it.kind === "tool" && (it.id === itemId || it.toolId === toolId)
              ? { ...it, result: content }
              : it,
          ),
        );
        break;
      }
      case "memory_diff": {
        const diff = ev.diff as
          | { added?: string[]; updated?: string[]; removed?: string[] }
          | undefined;
        if (diff) {
          const hl: Record<string, DiffHighlight> = {};
          for (const k of diff.added ?? []) {
            hl[k] = "added";
          }
          for (const k of diff.updated ?? []) {
            hl[k] = "updated";
          }
          for (const k of diff.removed ?? []) {
            hl[k] = "removed";
          }
          setHighlights(hl);
          if (sessionIdRef.current) {
            void refreshMemory(sessionIdRef.current);
          }
        }
        break;
      }
      case "final": {
        const text = String(ev.text ?? "");
        if (state.assistantId === null) {
          const id = uid();
          state.assistantId = id;
          setChatItems((prev) => [...prev, { kind: "assistant", id, text }]);
        } else {
          const aid = state.assistantId;
          setChatItems((prev) =>
            prev.map((it) =>
              it.kind === "assistant" && it.id === aid ? { ...it, text } : it,
            ),
          );
        }
        break;
      }
      case "error": {
        const message = String(ev.message ?? "unknown error");
        setChatItems((prev) => [
          ...prev,
          { kind: "error", id: uid(), message },
        ]);
        setError(message);
        break;
      }
      case "trace_ready": {
        if (ev.trace && typeof ev.trace === "object") {
          const t = ev.trace as TraceData;
          setTrace(t);
          const dp = t.promptTokens ?? 0;
          const dc = t.completionTokens ?? 0;
          setGlobalTokens((prev) => {
            const next: TokenUsageDto = {
              promptTokens: prev.promptTokens + dp,
              completionTokens: prev.completionTokens + dc,
              totalTokens: prev.totalTokens + dp + dc,
              chatCount: prev.chatCount + 1,
            };
            try {
              localStorage.setItem(GLOBAL_TOKENS_KEY, JSON.stringify(next));
            } catch {
              /* ignore */
            }
            return next;
          });
        }
        break;
      }
      case "done": {
        /* stream end handled by caller */
        break;
      }
      default:
        break;
    }
  };

  const runStream = async (
    start: (onEvent: (ev: SseEvent) => void, signal: AbortSignal) => Promise<void>,
    userText: string | null,
  ): Promise<void> => {
    if (sessionId === null) {
      return;
    }
    abortRef.current?.abort();
    const ac = new AbortController();
    abortRef.current = ac;
    setStreaming(true);
    setError(null);
    setHighlights({});
    setNextRoundHint(null);

    if (userText !== null) {
      setChatItems((prev) => [
        ...prev,
        { kind: "user", id: uid(), text: userText },
      ]);
    }

    const state = { assistantId: null as string | null, toolMap: new Map<string, string>() };

    try {
      await start((ev) => applySseEvent(ev, state), ac.signal);
      if (sessionIdRef.current) {
        await refreshMemory(sessionIdRef.current);
        try {
          const t = await getTrace(sessionIdRef.current);
          setTrace(t);
        } catch {
          /* ignore */
        }
        await refreshSessions();
      }
    } catch (e) {
      if ((e as Error).name === "AbortError") {
        return;
      }
      const msg = (e as Error).message;
      setError(msg);
      setChatItems((prev) => [...prev, { kind: "error", id: uid(), message: msg }]);
    } finally {
      setStreaming(false);
    }
  };

  const handleSend = async (): Promise<void> => {
    const text = draft.trim();
    if (text === "" || sessionId === null || streaming) {
      return;
    }
    setDraft("");
    const id = sessionId;
    if (autoGenEnabled && (env?.systemPrompt ?? "").trim() === "") {
      try {
        setBusy(true);
        setMemStatus("正在自动生成人设…");
        const { setting } = await autoGenSetting(id);
        if (setting.trim() !== "") {
          setEnv((prev) => (prev ? { ...prev, systemPrompt: setting } : prev));
          setMemStatus("已自动生成人设");
        } else {
          setMemStatus("人设生成为空，使用空人设继续");
        }
      } catch (e) {
        setError((e as Error).message);
        return;
      } finally {
        setBusy(false);
      }
    }
    void runStream(
      (onEvent, signal) => streamChat(id, text, onEvent, signal),
      text,
    );
  };

  const handleSummarize = (): void => {
    if (sessionId === null || streaming) {
      return;
    }
    const id = sessionId;
    void runStream(
      (onEvent, signal) => streamSummarize(id, onEvent, signal),
      "[summarize]",
    );
  };

  const handleGenerateSetting = async (): Promise<void> => {
    if (sessionId === null) {
      return;
    }
    try {
      setBusy(true);
      setError(null);
      setMemStatus("正在生成人设…");
      const { setting } = await autoGenSetting(sessionId);
      if (setting.trim() !== "") {
        setEnv((prev) => (prev ? { ...prev, systemPrompt: setting } : prev));
        setMemStatus("已生成人设");
      } else {
        setMemStatus("人设生成返回为空");
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const handleToggleAutoGen = async (enabled: boolean): Promise<void> => {
    if (sessionId === null) {
      setAutoGenEnabled(enabled);
      return;
    }
    setAutoGenEnabled(enabled);
    try {
      await setAutoGenSetting(sessionId, enabled);
      await refreshSessions();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const handleImportGameMemory = async (name: string): Promise<void> => {
    if (sessionId === null) {
      return;
    }
    try {
      setBusy(true);
      setError(null);
      const { content } = await getGameMemory(name);
      await importMemory(sessionId, content);
      await refreshMemory(sessionId);
      setMemStatus(`已从游戏目录导入 ${name}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const handleMemSave = async (
    key: string,
    value: string,
    importance: string,
  ): Promise<void> => {
    if (sessionId === null) {
      return;
    }
    try {
      await putMemory(sessionId, { key, value, importance });
      await refreshMemory(sessionId);
      setMemStatus(`已保存 ${key}`);
      setNextRoundHint(NEXT_ROUND_HINT);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const handleMemDelete = async (key: string): Promise<void> => {
    if (sessionId === null) {
      return;
    }
    try {
      await deleteMemory(sessionId, key);
      setHighlights((prev) => ({ ...prev, [key]: "removed" }));
      await refreshMemory(sessionId);
      setMemStatus(`已删除 ${key}`);
      setNextRoundHint(NEXT_ROUND_HINT);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const handleMemImport = async (json: string): Promise<void> => {
    if (sessionId === null) {
      return;
    }
    try {
      await importMemory(sessionId, json);
      await refreshMemory(sessionId);
      setHighlights({});
      setMemStatus("导入成功");
      setNextRoundHint(NEXT_ROUND_HINT);
      await refreshSessions();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const handleMemExport = async (format: ExportFormat): Promise<void> => {
    if (sessionId === null) {
      return;
    }
    try {
      const res = await exportMemory(sessionId, format);
      const ok = await copyText(res.content);
      setMemStatus(ok ? `已复制 export (${format})` : `导出成功但复制失败 (${format})`);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const handleRefreshTrace = async (): Promise<void> => {
    if (sessionId === null) {
      return;
    }
    try {
      setTrace(await getTrace(sessionId));
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const noSession = sessionId === null;
  const currentTokens = sessions.find((s) => s.id === sessionId)?.tokenUsage ?? null;

  return (
    <div className="app">
      <header className="app-header">
        <h1>记忆系统测试台</h1>
        <span className="session-id">
          {sessionId !== null ? `会话：${sessionId}` : "无会话"}
        </span>
        <span className="token-stats">
          {currentTokens !== null ? (
            <span title="当前会话累计 token">
              会话 <b>{currentTokens.totalTokens}</b>
              <span className="token-sub"> · p{currentTokens.promptTokens}/c{currentTokens.completionTokens} · {currentTokens.chatCount}轮</span>
            </span>
          ) : null}
          <span title="所有会话累计 token（本地持久化）">
            全局 <b>{globalTokens.totalTokens}</b>
            <span className="token-sub"> · p{globalTokens.promptTokens}/c{globalTokens.completionTokens} · {globalTokens.chatCount}轮</span>
          </span>
        </span>
      </header>
      {error !== null ? (
        <div className="error-banner" role="alert">
          {error}
          <button
            type="button"
            style={{ marginLeft: 8 }}
            onClick={() => setError(null)}
          >
            关闭
          </button>
        </div>
      ) : null}
      <div className="app-main">
        <Sidebar
          sessions={sessions}
          sessionId={sessionId}
          scenarios={scenarios}
          selectedScenarioId={selectedScenarioId}
          env={env}
          config={config}
          nextRoundHint={nextRoundHint}
          busy={busy || streaming}
          onSelectSession={(id) => void selectSession(id)}
          onCreateSession={() => void handleCreateSession()}
          onDeleteSession={(id) => void handleDeleteSession(id)}
          onScenarioChange={setSelectedScenarioId}
          onLoadScenario={() => void handleLoadScenario()}
          userScenarios={userScenarios}
          newScenarioName={newScenarioName}
          onNewScenarioNameChange={setNewScenarioName}
          onSaveScenario={() => void handleSaveScenario()}
          onLoadUserScenario={(name) => void handleLoadUserScenario(name)}
          onDeleteUserScenario={(name) => void handleDeleteUserScenario(name)}
          autoGenEnabled={autoGenEnabled}
          onToggleAutoGen={(v) => void handleToggleAutoGen(v)}
          onGenerateSetting={() => void handleGenerateSetting()}
          onEnvChange={handleEnvChange}
          onConfigChange={handleConfigChange}
        />
        <Chat
          items={chatItems}
          streaming={streaming}
          disabled={noSession || busy}
          draft={draft}
          onDraftChange={setDraft}
          onSend={handleSend}
          onSummarize={handleSummarize}
        />
        <MemoryInspector
          memories={memories}
          highlights={highlights}
          disabled={noSession || busy || streaming}
          status={memStatus}
          gameMemories={gameMemories}
          onSave={(k, v, i) => void handleMemSave(k, v, i)}
          onDelete={(k) => void handleMemDelete(k)}
          onImport={(j) => void handleMemImport(j)}
          onImportGameMemory={(name) => void handleImportGameMemory(name)}
          onExport={(f) => void handleMemExport(f)}
          onAdd={(k, v, i) => void handleMemSave(k, v, i)}
        />
      </div>
      <DebugPanel
        trace={trace}
        lastContext={lastContext}
        onRefresh={() => void handleRefreshTrace()}
        disabled={noSession}
      />
    </div>
  );
}
