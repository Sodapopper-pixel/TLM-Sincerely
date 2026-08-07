import { useEffect, useRef, type FormEvent, type KeyboardEvent } from "react";
import { toolCallSummary } from "../api";

export type ChatItem =
  | { kind: "user"; id: string; text: string }
  | { kind: "assistant"; id: string; text: string }
  | {
      kind: "tool";
      id: string;
      toolId: string;
      name: string;
      arguments: string;
      result?: string;
    }
  | { kind: "error"; id: string; message: string };

export interface ChatProps {
  items: ChatItem[];
  streaming: boolean;
  disabled: boolean;
  draft: string;
  onDraftChange: (v: string) => void;
  onSend: () => void;
  onSummarize: () => void;
}

export function Chat(props: ChatProps): JSX.Element {
  const { items, streaming, disabled, draft, onDraftChange, onSend, onSummarize } = props;
  const logRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = logRef.current;
    if (el) {
      el.scrollTop = el.scrollHeight;
    }
  }, [items, streaming]);

  const submit = (e: FormEvent): void => {
    e.preventDefault();
    onSend();
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>): void => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      if (!streaming && !disabled && draft.trim() !== "") {
        onSend();
      }
    }
  };

  return (
    <section className="panel chat-panel">
      <div className="panel-header">
        <span>对话</span>
        {streaming ? <span className="streaming-flag">生成中…</span> : null}
      </div>
      <div className="chat-log" ref={logRef}>
        {items.length === 0 ? (
          <p className="empty">发送消息开始对话。SSE 会实时追加 tool 条与女仆文本。</p>
        ) : (
          items.map((item) => {
            if (item.kind === "user") {
              return (
                <div key={item.id} className="bubble user">
                  <span className="role">玩家</span>
                  {item.text}
                </div>
              );
            }
            if (item.kind === "assistant") {
              return (
                <div key={item.id} className="bubble assistant">
                  <span className="role">女仆</span>
                  {item.text || (streaming ? "…" : "")}
                </div>
              );
            }
            if (item.kind === "error") {
              return (
                <div key={item.id} className="bubble error">
                  <span className="role">错误</span>
                  {item.message}
                </div>
              );
            }
            const summary = toolCallSummary(item.name, item.arguments);
            return (
              <details key={item.id} className="tool-bar" open={item.result === undefined}>
                <summary>
                  {summary}
                  {item.result === undefined ? " …" : ""}
                </summary>
                <div className="tool-detail">
                  <div>
                    <strong>调用 id</strong> {item.toolId}
                  </div>
                  <div>
                    <strong>参数</strong>
                    <pre>{item.arguments}</pre>
                  </div>
                  {item.result !== undefined ? (
                    <div>
                      <strong>结果</strong>
                      <pre>{item.result}</pre>
                    </div>
                  ) : (
                    <div className="empty">等待结果…</div>
                  )}
                </div>
              </details>
            );
          })
        )}
      </div>
      <form className="chat-composer" onSubmit={submit}>
        <textarea
          value={draft}
          onChange={(e) => onDraftChange(e.target.value)}
          onKeyDown={onKeyDown}
          placeholder="输入消息，Enter 发送，Shift+Enter 换行"
          disabled={disabled || streaming}
        />
        <div className="actions">
          <button type="submit" disabled={disabled || streaming || draft.trim() === ""}>
            发送
          </button>
          <button
            type="button"
            onClick={onSummarize}
            disabled={disabled || streaming}
            title="POST /summarize — 触发补记"
          >
            补记
          </button>
        </div>
      </form>
    </section>
  );
}
