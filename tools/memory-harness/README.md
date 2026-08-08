# tlm-memory-harness

简易记忆系统的**游戏外可复现测试环境**。不启动 Minecraft，即可调试《车万女仆：真心为你》的记忆域逻辑、主模组 AI 协议（context 注入 / tool 循环 / guidance system 注入）、记忆相关 AI 功能，并通过 Web UI 观测完整 prompt/tool trace。

> 契约基准见 [CONTRACT.md](./CONTRACT.md)（对照 TLM 1.5.3-forge，每个值标注来源类名）。  
> 设计计划见 `docs/plans/简易记忆系统游戏外测试环境计划.md`。

## 它解决什么

- 改 guidance / tool description / preview 算法后反馈慢、难复现；
- 「该记不记 / 不该记却记 / 预览看得到但 recall 不对」无法稳定复现；
- 配置旋钮（`CoreLimit` / `PreviewLength` / `MaxMemories`）对模型行为的影响缺乏可观测手段。

harness 是**镜像不是实验场**：行为改进一律先落 Java 权威实现 → 重新生成 golden → 同步 TS，禁止在 TS 侧单方面改行为。

## 目录结构

```
tools/memory-harness/
  packages/
    core/      # 记忆域镜像 + JSON store + preview + config + CLI
    agent/     # VirtualMaid / ContextBuilder / ToolRegistry / AgentLoop / LLM transport
    server/    # HTTP + SSE API + session 管理
    web/       # Vite + React 三栏 UI
  fixtures/
    golden/    # 由 Java 权威实现一键生成的黄金样例（preview/json/set）
    scenarios/ # 场景回归（含合成 recording）
    skills/    # skill 文件（M-2.4 后 memory-guidance 已移除，目录可为空）
  CONTRACT.md  # 协议契约（对照 TLM 1.5.3）
  data/        # 运行时数据（gitignore）
```

## 前置要求

- Node.js >= 18（开发环境为 node 24）
- Java 17（仅生成/校验 golden 时需要，见下文「与模组的 golden 锁」）
- （可选）可访问的 OpenAI 兼容 LLM API，用于 live/record 模式

## 安装

```powershell
cd tools/memory-harness
npm install
```

## 构建与测试

```powershell
# 类型检查（各包）
npm run typecheck

# 构建各包 dist（core/agent/server）
npm run build

# 全量测试（golden + agent loop + 场景 + server）
npm test

# 仅 golden（TS 镜像 vs Java 权威 fixtures）
npm run test:golden

# 仅场景回归（离线，合成 recording，无需 LLM key）
npm run test:scenarios
```

当前 30 个测试：core golden 16、agent loop 5、场景 4、transport 2、server 3。

## 启动

需要两个终端（server 与 web）：

```powershell
# 终端 1：先构建 server，再启动（默认 127.0.0.1:7421）
npm run build
npm run dev:server

# 终端 2：启动前端（vite dev，/api 代理到 7421）
npm run dev:web
```

打开 vite 提示的地址（通常 http://127.0.0.1:5173）。

无 LLM key 时可用 mock 模式冒烟：在终端 1 前 `$env:LLM_TRANSPORT="mock"`（PowerShell），server 会返回固定文案，可用于验证 UI/SSE 管线。

## WebUI 功能

- 三栏布局：左栏会话/场景/env/人设/配置，中栏对话（含 tool 调用折叠条与 SSE 实时流），右栏记忆 Inspector（diff 高亮、导入/导出）。
- **会话恢复**：当前会话 ID 持久化到 localStorage，刷新/切后台不丢失；刷新后从服务端拉取 history 重建对话气泡。
- **女仆人设（systemPrompt）**：留空时可由 LLM 根据女仆名自动生成（镜像主模组 `autoGenSetting`，默认开启可关）；也可手动「生成人设」。无人设时不发送 system 消息（与主模组一致）。
- **env 固定字段下拉**：weather / dimension / schedule(DAY/NIGHT/ALL) / activity(idle/work/rest) 为下拉选择（枚举经主模组源码证实）。
- **token 统计**：顶栏显示当前会话累计 + 全局累计（localStorage 持久化）token；mock 模式补估算值使计数器可动。
- **debug 栏**：展示注入的 tools / 可用 skills / 上次 context / 原始 messages；顶边可拖动调整高度（VSCode 风格，高度持久化）。
- **用户场景**：可把当前会话的 env+记忆+历史+配置保存为命名场景，加载时新建会话套用（与只读测试夹具区分）。
- 界面已汉化（术语如 core/archive/key/value/promptTokens 等保留英文）。

## .env 配置

复制 `.env.example` 为 `.env`：

| 变量 | 说明 |
|------|------|
| `LLM_BASE_URL` | OpenAI 兼容 API 基址 |
| `LLM_API_KEY` | API Key |
| `LLM_MODEL` | 模型名 |
| `LLM_TRANSPORT` | `live` \| `record` \| `replay` \| `mock` |
| `SERVER_HOST` / `SERVER_PORT` | 默认 `127.0.0.1` / `7421` |

- `live`：真实调用 provider，手聊调参。
- `record`：真实调用并按会话落盘 `data/recordings/<sessionId>/turn-N.json`，把「表现良好」的行为留成快照。
- `replay`：不出网，按轮序回放录制响应，用于场景回归 CI。
- `mock`：返回固定文案，无 key 冒烟。

## 导入游戏记忆

游戏内记忆位于 `<gameDir>/config/tlm_sincerely/maid_memories/<uuid>.json`（dev 环境即 `run/config/tlm_sincerely/maid_memories/`）。Web 右栏「记忆 Inspector」的「导入 / 导出」区默认列出该固定目录下的女仆记忆文件供选择导入；也可用文件选择器导入任意路径的 `.json`。格式与游戏内 1:1（见 CONTRACT §9）。默认目录可用环境变量 `TLM_GAME_MEM_DIR` 覆盖。

## 场景回归

`fixtures/scenarios/*.json` 每个含 `opening`、`recording`（合成的 LLM 响应序列）、`assertions`（结构断言：toolCalls / memoryHas / memoryAbsent / finalContains / contextContains）。`npm run test:scenarios` 离线运行。

> 说明：合成 recording 测的是 **harness 机制**（agent loop / tool 执行 / 断言框架），不测「guidance 文案对模型行为的实际影响」——后者需 live LLM：先 `LLM_TRANSPORT=record` 跑一次录制，再 `replay` 离线回归，即可对比改 guidance 前后的 tool 轨迹。

新增场景：在 `fixtures/scenarios/` 加 `<id>.json`，runner 自动发现。

## 与模组的 golden 锁

TS 镜像必须与 Java 权威实现一致。golden 期望值**只能由 Java 一键生成**，禁止手抄：

```powershell
# Java 侧（项目根，需 Java 17）
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
.\gradlew.bat generateGolden   # 从 MaidMemory 权威实现重新生成 fixtures/golden/
.\gradlew.bat goldenCheck      # 校验 Java 权威实现与已提交 golden 一致
```

TS 侧 `npm run test:golden` 读取同一批 golden 并断言 TS 镜像一致。任一侧改动权威算法都会立即暴露失步。

> M-2.4 后 `memory-guidance` skill 已移除（改为 `MemoryGuidanceMixin` 注入 system 引导，见 CONTRACT §10），原 skill 一致性测试随之删除。

## 与模组的关系

- 记忆域（`MaidMemory` / `MaidMemoryManager`）TS 镜像在 `packages/core`，行为对齐 CONTRACT §4/§8/§9。
- M-1 已在 Java 侧修复：F1（超限 core 降级预览）、F2（满容判重用 trim 后 key）、F3（移除 key enum）。
- M-2 强化已同步：search/merge/满容自动淘汰/访问统计/source/keys_only 预览/system guidance 注入（替代 skill）/维护模式收窄/11 项配置。harness TS 镜像与 golden 一致。
- 主模组 AI 协议（消息结构 / tool 循环护栏 16 轮与重复批 2 / context 注入）对齐 CONTRACT §2/§5。
