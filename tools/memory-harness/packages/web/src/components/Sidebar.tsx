import type { ChangeEvent } from "react";
import type {
  MemoryConfigDto,
  ScenarioDto,
  SessionSummary,
  UserScenarioSummaryDto,
  VirtualMaidDto,
} from "../api";

export interface SidebarProps {
  sessions: SessionSummary[];
  sessionId: string | null;
  scenarios: ScenarioDto[];
  selectedScenarioId: string;
  env: VirtualMaidDto | null;
  config: MemoryConfigDto | null;
  nextRoundHint: string | null;
  busy: boolean;
  onSelectSession: (id: string) => void;
  onCreateSession: () => void;
  onDeleteSession: (id: string) => void;
  onScenarioChange: (id: string) => void;
  onLoadScenario: () => void;
  userScenarios: UserScenarioSummaryDto[];
  newScenarioName: string;
  onNewScenarioNameChange: (v: string) => void;
  onSaveScenario: () => void;
  onLoadUserScenario: (name: string) => void;
  onDeleteUserScenario: (name: string) => void;
  autoGenEnabled: boolean;
  onToggleAutoGen: (v: boolean) => void;
  onGenerateSetting: () => void;
  onEnvChange: (patch: Partial<VirtualMaidDto>) => void;
  onConfigChange: (patch: Partial<MemoryConfigDto>) => void;
}

const ENV_FIELDS: Array<{
  key: keyof VirtualMaidDto;
  label: string;
  kind: "text" | "number" | "bool";
  options?: readonly string[];
}> = [
  { key: "name", label: "name", kind: "text" },
  { key: "userName", label: "userName", kind: "text" },
  { key: "weather", label: "weather", kind: "text", options: ["clear", "rain", "thunder"] },
  { key: "timeOfDay", label: "timeOfDay", kind: "number" },
  { key: "health", label: "health", kind: "number" },
  { key: "maxHealth", label: "maxHealth", kind: "number" },
  { key: "userHealth", label: "userHealth", kind: "number" },
  { key: "task", label: "task", kind: "text" },
  { key: "schedule", label: "schedule", kind: "text", options: ["DAY", "NIGHT", "ALL"] },
  { key: "activity", label: "activity", kind: "text", options: ["idle", "work", "rest"] },
  { key: "dimension", label: "dimension", kind: "text", options: ["overworld", "the_nether", "the_end"] },
  { key: "biome", label: "biome", kind: "text" },
  { key: "language", label: "language", kind: "text" },
  { key: "isFollowing", label: "isFollowing", kind: "bool" },
  { key: "sitting", label: "sitting", kind: "bool" },
  { key: "isSleeping", label: "isSleeping", kind: "bool" },
];

function shortId(id: string): string {
  return id.length > 12 ? `${id.slice(0, 8)}…` : id;
}

export function Sidebar(props: SidebarProps): JSX.Element {
  const {
    sessions,
    sessionId,
    scenarios,
    selectedScenarioId,
    env,
    config,
    nextRoundHint,
    busy,
    onSelectSession,
    onCreateSession,
    onDeleteSession,
    onScenarioChange,
    onLoadScenario,
    userScenarios,
    newScenarioName,
    onNewScenarioNameChange,
    onSaveScenario,
    onLoadUserScenario,
    onDeleteUserScenario,
    autoGenEnabled,
    onToggleAutoGen,
    onGenerateSetting,
    onEnvChange,
    onConfigChange,
  } = props;

  const disabled = busy || sessionId === null;

  const onText =
    (key: keyof VirtualMaidDto) =>
    (e: ChangeEvent<HTMLInputElement>): void => {
      onEnvChange({ [key]: e.target.value });
    };

  const onNum =
    (key: keyof VirtualMaidDto) =>
    (e: ChangeEvent<HTMLInputElement>): void => {
      const n = Number(e.target.value);
      if (!Number.isNaN(n)) {
        onEnvChange({ [key]: n });
      }
    };

  const onBool =
    (key: keyof VirtualMaidDto) =>
    (e: ChangeEvent<HTMLInputElement>): void => {
      onEnvChange({ [key]: e.target.checked });
    };

  const onSelect =
    (key: keyof VirtualMaidDto) =>
    (e: ChangeEvent<HTMLSelectElement>): void => {
      onEnvChange({ [key]: e.target.value });
    };

  return (
    <aside className="panel sidebar">
      <div className="panel-header">
        <span>会话列表</span>
        <button type="button" onClick={onCreateSession} disabled={busy}>
          新建
        </button>
      </div>
      <div className="panel-body">
        <div className="panel-section">
          {sessions.length === 0 ? (
            <p className="empty">无会话，点击「新建」</p>
          ) : (
            <ul className="session-list">
              {sessions.map((s) => (
                <li key={s.id} className={s.id === sessionId ? "active" : ""}>
                  <button
                    type="button"
                    className="session-pick"
                    onClick={() => onSelectSession(s.id)}
                    disabled={busy}
                  >
                    {shortId(s.id)}
                    <span className="session-meta">
                      {s.maidName} · 记忆 {s.memoryCount} · 历史 {s.historyLength}
                    </span>
                  </button>
                  <button
                    type="button"
                    className="session-del"
                    title="删除会话"
                    onClick={() => onDeleteSession(s.id)}
                    disabled={busy}
                  >
                    ×
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="panel-section">
          <h3>内置场景</h3>
          <div className="row">
            <select
              value={selectedScenarioId}
              onChange={(e) => onScenarioChange(e.target.value)}
              disabled={busy}
            >
              <option value="">— 选择场景 —</option>
              {scenarios.map((sc) => {
                const sid = String(sc.id ?? sc.file ?? "");
                return (
                  <option key={sid} value={sid}>
                    {sid}
                    {sc.intent ? ` · ${sc.intent}` : ""}
                  </option>
                );
              })}
            </select>
            <button
              type="button"
              onClick={onLoadScenario}
              disabled={disabled || selectedScenarioId === ""}
            >
              加载
            </button>
          </div>
          <p className="hint">加载会重置 env + memory（及 history=[]）</p>
        </div>

        <div className="panel-section">
          <h3>我的场景</h3>
          <div className="row">
            <input
              placeholder="场景名"
              value={newScenarioName}
              onChange={(e) => onNewScenarioNameChange(e.target.value)}
              disabled={busy}
            />
            <button
              type="button"
              disabled={disabled || newScenarioName.trim() === ""}
              onClick={onSaveScenario}
            >
              保存当前为场景
            </button>
          </div>
          <p className="hint">保存当前会话的 env+记忆+历史+配置为可复现场景；加载会新建会话套用</p>
          {userScenarios.length === 0 ? (
            <p className="empty">无用户场景</p>
          ) : (
            <ul className="session-list user-scenario-list">
              {userScenarios.map((s) => (
                <li key={s.name}>
                  <span className="user-scenario-name">
                    {s.name}
                    <span className="session-meta"> {new Date(s.savedAt).toLocaleDateString()}</span>
                  </span>
                  <span className="row user-scenario-actions">
                    <button type="button" disabled={busy} onClick={() => onLoadUserScenario(s.name)}>
                      加载
                    </button>
                    <button type="button" disabled={busy} onClick={() => onDeleteUserScenario(s.name)}>
                      删
                    </button>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="panel-section">
          <h3>女仆环境（env）</h3>
          {env === null ? (
            <p className="empty">选择会话后可编辑</p>
          ) : (
            <>
              {ENV_FIELDS.map((f) => {
                const val = env[f.key];
                if (f.kind === "bool") {
                  return (
                    <div className="checkbox-row" key={String(f.key)}>
                      <input
                        type="checkbox"
                        id={`env-${String(f.key)}`}
                        checked={Boolean(val)}
                        onChange={onBool(f.key)}
                        disabled={disabled}
                      />
                      <label htmlFor={`env-${String(f.key)}`}>{f.label}</label>
                    </div>
                  );
                }
                if (f.options) {
                  const cur = val === undefined || val === null ? "" : String(val);
                  const inList = f.options.includes(cur);
                  return (
                    <div className="field" key={String(f.key)}>
                      <label htmlFor={`env-${String(f.key)}`}>{f.label}</label>
                      <select
                        id={`env-${String(f.key)}`}
                        value={cur}
                        onChange={onSelect(f.key)}
                        disabled={disabled}
                      >
                        {!inList && cur !== "" ? <option value={cur}>{cur}</option> : null}
                        {f.options.map((o) => (
                          <option key={o} value={o}>
                            {o}
                          </option>
                        ))}
                      </select>
                    </div>
                  );
                }
                return (
                  <div className="field" key={String(f.key)}>
                    <label htmlFor={`env-${String(f.key)}`}>{f.label}</label>
                    <input
                      id={`env-${String(f.key)}`}
                      className={f.kind === "number" ? "num-input" : undefined}
                      type={f.kind === "number" ? "number" : "text"}
                      value={val === undefined || val === null ? "" : String(val)}
                      onChange={f.kind === "number" ? onNum(f.key) : onText(f.key)}
                      disabled={disabled}
                    />
                  </div>
                );
              })}
            </>
          )}
        </div>

        <div className="panel-section">
          <h3>女仆人设（systemPrompt）</h3>
          {env === null ? (
            <p className="empty">选择会话后可编辑</p>
          ) : (
            <>
              <textarea
                id="env-systemPrompt"
                value={env.systemPrompt ?? ""}
                onChange={(e) => onEnvChange({ systemPrompt: e.target.value })}
                disabled={disabled}
                rows={4}
              />
              <p className="hint">由主模组管理：玩家在游戏内对着女仆按 T 键编辑的人设提示词；本附属 tlm_sincerely 不参与定义。是否支持变量(PlaceholderAPI)展开待核实。留空时（开启下方选项）可由 LLM 根据女仆名自动生成。</p>
              <div className="checkbox-row">
                <input
                  type="checkbox"
                  id="cfg-auto-gen"
                  checked={autoGenEnabled}
                  onChange={(e) => onToggleAutoGen(e.target.checked)}
                  disabled={busy}
                />
                <label htmlFor="cfg-auto-gen">无人设时自动生成人设</label>
              </div>
              <button
                type="button"
                onClick={onGenerateSetting}
                disabled={disabled || busy}
                title="调用 LLM 根据女仆名生成人设并写入"
              >
                生成人设
              </button>
            </>
          )}
        </div>

        <div className="panel-section">
          <h3>记忆配置</h3>
          {config === null ? (
            <p className="empty">选择会话后可编辑</p>
          ) : (
            <>
              <div className="checkbox-row">
                <input
                  type="checkbox"
                  id="cfg-enabled"
                  checked={config.enabled}
                  onChange={(e) => onConfigChange({ enabled: e.target.checked })}
                  disabled={disabled}
                />
                <label htmlFor="cfg-enabled">enabled</label>
              </div>
              <div className="field">
                <label htmlFor="cfg-max">maxMemories (1–200)</label>
                <input
                  id="cfg-max"
                  type="number"
                  min={1}
                  max={200}
                  value={config.maxMemories}
                  onChange={(e) => {
                    const n = Number(e.target.value);
                    if (!Number.isNaN(n)) {
                      onConfigChange({ maxMemories: n });
                    }
                  }}
                  disabled={disabled}
                />
              </div>
              <div className="field">
                <label htmlFor="cfg-core">coreLimit (0–50)</label>
                <input
                  id="cfg-core"
                  type="number"
                  min={0}
                  max={50}
                  value={config.coreLimit}
                  onChange={(e) => {
                    const n = Number(e.target.value);
                    if (!Number.isNaN(n)) {
                      onConfigChange({ coreLimit: n });
                    }
                  }}
                  disabled={disabled}
                />
              </div>
              <div className="field">
                <label htmlFor="cfg-preview">contextPreviewLength (10–200)</label>
                <input
                  id="cfg-preview"
                  type="number"
                  min={10}
                  max={200}
                  value={config.contextPreviewLength}
                  onChange={(e) => {
                    const n = Number(e.target.value);
                    if (!Number.isNaN(n)) {
                      onConfigChange({ contextPreviewLength: n });
                    }
                  }}
                  disabled={disabled}
                />
              </div>
            </>
          )}
          {nextRoundHint !== null && nextRoundHint !== "" ? (
            <p className="hint warn">{nextRoundHint}</p>
          ) : null}
        </div>
      </div>
    </aside>
  );
}
