import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import type { TraceData } from "../api";
import { copyText } from "../api";

export interface DebugPanelProps {
  trace: TraceData | null;
  lastContext: string | null;
  onRefresh: () => void;
  disabled: boolean;
}

const RESIZE_KEY = "tlm-harness:debug-height";
const DEFAULT_HEIGHT = 220;
const MIN_HEIGHT = 80;

function loadDebugHeight(): number {
  try {
    const v = Number(localStorage.getItem(RESIZE_KEY));
    if (Number.isFinite(v) && v >= MIN_HEIGHT) {
      return v;
    }
  } catch {
    /* ignore */
  }
  return DEFAULT_HEIGHT;
}

export function DebugPanel(props: DebugPanelProps): JSX.Element {
  const { trace, lastContext, onRefresh, disabled } = props;
  const [open, setOpen] = useState(true);
  const [copyMsg, setCopyMsg] = useState<string | null>(null);
  const [debugHeight, setDebugHeight] = useState<number>(loadDebugHeight);
  const drawerRef = useRef<HTMLDivElement>(null);
  const dragRef = useRef<{ startY: number; startH: number } | null>(null);

  const onResizeDown = (e: ReactPointerEvent<HTMLDivElement>): void => {
    if (!open) {
      return;
    }
    const el = drawerRef.current;
    const h = el ? el.getBoundingClientRect().height : debugHeight;
    dragRef.current = { startY: e.clientY, startH: h };
    e.preventDefault();
  };

  useEffect((): (() => void) => {
    const onMove = (e: PointerEvent): void => {
      if (dragRef.current === null) {
        return;
      }
      const { startY, startH } = dragRef.current;
      const next = Math.min(
        Math.max(MIN_HEIGHT, startH + (startY - e.clientY)),
        window.innerHeight * 0.85,
      );
      setDebugHeight(next);
    };
    const onUp = (): void => {
      if (dragRef.current !== null) {
        dragRef.current = null;
        setDebugHeight((h) => {
          try {
            localStorage.setItem(RESIZE_KEY, String(h));
          } catch {
            /* ignore */
          }
          return h;
        });
      }
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
    return () => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
    };
  }, []);

  const messagesJson =
    trace !== null ? JSON.stringify(trace.messages ?? [], null, 2) : "[]";

  const doCopy = async (): Promise<void> => {
    const ok = await copyText(messagesJson);
    setCopyMsg(ok ? "已复制 messages JSON" : "复制失败");
    window.setTimeout(() => setCopyMsg(null), 2000);
  };

  return (
    <div
      ref={drawerRef}
      className={`debug-drawer${open ? "" : " collapsed"}`}
      style={open ? { height: `${debugHeight}px` } : undefined}
    >
      {open ? (
        <div className="debug-resizer" onPointerDown={onResizeDown} title="拖动调整高度" />
      ) : null}
      <div className="panel-header">
        <span>
          <button type="button" onClick={() => setOpen((v) => !v)}>
            {open ? "▾" : "▸"} 调试
          </button>
        </span>
        <span className="row" style={{ flex: "0 0 auto", gap: 6 }}>
          {copyMsg !== null ? <span className="hint">{copyMsg}</span> : null}
          <button type="button" onClick={() => void doCopy()} disabled={disabled || trace === null}>
            复制 messages JSON
          </button>
          <button type="button" onClick={onRefresh} disabled={disabled}>
            刷新 trace
          </button>
        </span>
      </div>
      {open ? (
        <div className="panel-body">
          <div className="debug-stats">
            <span>promptTokens: {trace?.promptTokens ?? "—"}</span>
            <span>completionTokens: {trace?.completionTokens ?? "—"}</span>
            <span>toolLogs: {trace?.toolLogs?.length ?? 0}</span>
            <span>messages: {trace?.messages?.length ?? 0}</span>
          </div>
          {lastContext !== null && lastContext !== "" ? (
            <>
              <h3 style={{ margin: "0 0 4px", fontSize: 12, color: "#aaa" }}>
                上次注入的 context
              </h3>
              <pre className="debug-json">{lastContext}</pre>
            </>
          ) : null}
          <h3 style={{ margin: "8px 0 4px", fontSize: 12, color: "#aaa" }}>
            原始 messages
          </h3>
          <pre className="debug-json">{messagesJson}</pre>
          {trace !== null && trace.toolLogs.length > 0 ? (
            <>
              <h3 style={{ margin: "8px 0 4px", fontSize: 12, color: "#aaa" }}>
                toolLogs
              </h3>
              <pre className="debug-json">{JSON.stringify(trace.toolLogs, null, 2)}</pre>
            </>
          ) : null}
          {trace !== null && Array.isArray(trace.tools) && trace.tools.length > 0 ? (
            <>
              <h3 style={{ margin: "8px 0 4px", fontSize: 12, color: "#aaa" }}>
                注入的 tools ({trace.tools.length})
              </h3>
              <pre className="debug-json">{JSON.stringify(trace.tools, null, 2)}</pre>
            </>
          ) : null}
          {trace !== null && Array.isArray(trace.skills) && trace.skills.length > 0 ? (
            <>
              <h3 style={{ margin: "8px 0 4px", fontSize: 12, color: "#aaa" }}>
                可用 skills ({trace.skills.length})
              </h3>
              <ul className="debug-skill-list">
                {trace.skills.map((s) => (
                  <li key={s.name}>
                    <code>{s.name}</code>
                    <span className="skill-desc"> {s.description}</span>
                  </li>
                ))}
              </ul>
            </>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
