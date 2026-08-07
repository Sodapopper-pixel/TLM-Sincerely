import { useRef, useState, type ChangeEvent } from "react";
import type { ExportFormat, GameMemorySummaryDto, MemoryEntryDto, MemoryMapDto } from "../api";

export type DiffHighlight = "added" | "updated" | "removed";

export interface MemoryInspectorProps {
  memories: MemoryMapDto | null;
  highlights: Record<string, DiffHighlight>;
  disabled: boolean;
  status: string | null;
  onSave: (key: string, value: string, importance: string) => void;
  onDelete: (key: string) => void;
  onImport: (json: string) => void;
  onImportGameMemory: (name: string) => void;
  onExport: (format: ExportFormat) => void;
  onAdd: (key: string, value: string, importance: string) => void;
  gameMemories: GameMemorySummaryDto[];
}

function sortEntries(
  memories: Record<string, MemoryEntryDto>,
): Array<[string, MemoryEntryDto]> {
  const entries = Object.entries(memories);
  entries.sort((a, b) => {
    const ac = a[1].importance === "core" ? 0 : 1;
    const bc = b[1].importance === "core" ? 0 : 1;
    if (ac !== bc) {
      return ac - bc;
    }
    return a[0].localeCompare(b[0]);
  });
  return entries;
}

export function MemoryInspector(props: MemoryInspectorProps): JSX.Element {
  const {
    memories,
    highlights,
    disabled,
    status,
    onSave,
    onDelete,
    onImport,
    onImportGameMemory,
    onExport,
    onAdd,
    gameMemories,
  } = props;

  const fileRef = useRef<HTMLInputElement>(null);
  const [selGameMem, setSelGameMem] = useState("");
  const [exportFormat, setExportFormat] = useState<ExportFormat>("json");
  const [newKey, setNewKey] = useState("");
  const [newValue, setNewValue] = useState("");
  const [newImp, setNewImp] = useState<"core" | "archive">("archive");
  const [edits, setEdits] = useState<
    Record<string, { value: string; importance: string }>
  >({});

  const list = memories ? sortEntries(memories.memories) : [];
  const removedKeys = Object.entries(highlights)
    .filter(([, h]) => h === "removed")
    .map(([k]) => k)
    .filter((k) => !memories?.memories[k]);

  const getEdit = (key: string, entry: MemoryEntryDto): { value: string; importance: string } => {
    return edits[key] ?? { value: entry.value, importance: entry.importance };
  };

  const onFile = (e: ChangeEvent<HTMLInputElement>): void => {
    const file = e.target.files?.[0];
    if (!file) {
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const text = typeof reader.result === "string" ? reader.result : "";
      onImport(text);
    };
    reader.readAsText(file);
    e.target.value = "";
  };

  return (
    <aside className="panel">
      <div className="panel-header">
        <span>记忆检视器</span>
        <span className="session-meta">{list.length} 条目</span>
      </div>
      <div className="panel-body">
        {status !== null && status !== "" ? <p className="hint">{status}</p> : null}

        <div className="panel-section import-export">
          <h3>导入 / 导出</h3>
          <div className="row">
            <input
              ref={fileRef}
              type="file"
              accept=".json,application/json,text/plain"
              onChange={onFile}
              disabled={disabled}
            />
          </div>
          <p className="hint">从游戏目录导入（run/config/tlm_sincerely/maid_memories）：</p>
          <div className="row">
            <select
              value={selGameMem}
              onChange={(e) => setSelGameMem(e.target.value)}
              disabled={disabled}
            >
              <option value="">- 选择女仆记忆 -</option>
              {gameMemories.map((g) => (
                <option key={g.name} value={g.name}>
                  {g.name}
                </option>
              ))}
            </select>
            <button
              type="button"
              disabled={disabled || selGameMem === ""}
              onClick={() => onImportGameMemory(selGameMem)}
            >
              导入
            </button>
          </div>
          <div className="row">
            <select
              value={exportFormat}
              onChange={(e) => setExportFormat(e.target.value as ExportFormat)}
              disabled={disabled}
            >
              <option value="json">json</option>
              <option value="text">text</option>
              <option value="context">context</option>
            </select>
            <button type="button" onClick={() => onExport(exportFormat)} disabled={disabled}>
              导出并复制
            </button>
          </div>
        </div>

        <div className="panel-section">
          <h3>新增条目</h3>
          <div className="field">
            <label htmlFor="mem-new-key">key</label>
            <input
              id="mem-new-key"
              value={newKey}
              onChange={(e) => setNewKey(e.target.value)}
              disabled={disabled}
            />
          </div>
          <div className="field">
            <label htmlFor="mem-new-val">value</label>
            <textarea
              id="mem-new-val"
              value={newValue}
              onChange={(e) => setNewValue(e.target.value)}
              disabled={disabled}
              rows={2}
            />
          </div>
          <div className="row">
            <select
              value={newImp}
              onChange={(e) => setNewImp(e.target.value as "core" | "archive")}
              disabled={disabled}
            >
              <option value="core">core</option>
              <option value="archive">archive</option>
            </select>
            <button
              type="button"
              disabled={disabled || newKey.trim() === "" || newValue.trim() === ""}
              onClick={() => {
                onAdd(newKey.trim(), newValue.trim(), newImp);
                setNewKey("");
                setNewValue("");
                setNewImp("archive");
              }}
            >
              保存
            </button>
          </div>
        </div>

        <div className="panel-section">
          <h3>条目列表</h3>
          {list.length === 0 && removedKeys.length === 0 ? (
            <p className="empty">无记忆</p>
          ) : (
            <ul className="mem-list">
              {list.map(([key, entry]) => {
                const edit = getEdit(key, entry);
                const hl = highlights[key];
                const cls =
                  hl === "added"
                    ? "mem-item hl-added"
                    : hl === "updated"
                      ? "mem-item hl-updated"
                      : "mem-item";
                const badge = entry.importance === "core" ? "★core" : "·archive";
                return (
                  <li key={key} className={cls}>
                    <div className="mem-key">
                      <span className="badge">{badge}</span>
                      <code>{key}</code>
                      {hl ? <span className="badge">[{hl}]</span> : null}
                    </div>
                    <textarea
                      value={edit.value}
                      onChange={(e) =>
                        setEdits((prev) => ({
                          ...prev,
                          [key]: { value: e.target.value, importance: edit.importance },
                        }))
                      }
                      disabled={disabled}
                      rows={2}
                    />
                    <div className="mem-actions">
                      <select
                        value={edit.importance === "core" ? "core" : "archive"}
                        onChange={(e) =>
                          setEdits((prev) => ({
                            ...prev,
                            [key]: { value: edit.value, importance: e.target.value },
                          }))
                        }
                        disabled={disabled}
                      >
                        <option value="core">core</option>
                        <option value="archive">archive</option>
                      </select>
                      <button
                        type="button"
                        disabled={disabled}
                        onClick={() => onSave(key, edit.value, edit.importance)}
                      >
                        保存
                      </button>
                      <button type="button" disabled={disabled} onClick={() => onDelete(key)}>
                        删除
                      </button>
                    </div>
                  </li>
                );
              })}
              {removedKeys.map((key) => (
                <li key={`rm-${key}`} className="mem-item hl-removed">
                  <div className="mem-key">
                    <span className="badge">·removed</span>
                    <code>{key}</code>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </aside>
  );
}
