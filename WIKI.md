# 《车万女仆：真心为你》Wiki

> 面向玩家与开发者的功能说明文档。本文只描述当前版本的功能、命令、配置、兼容边界与代码索引，不记录开发历史。

## 项目信息

| 项目 | 内容 |
|---|---|
| 模组名称 | 《车万女仆：真心为你》 |
| Mod ID | `tlm_sincerely` |
| 平台 | Forge 1.20.1 |
| Java | 17 |
| 版本 | 0.2.0-beta |
| 主模组依赖 | Touhou Little Maid ≥ 1.5.3 |
| 必需依赖 | Cloth Config ≥ 11 |
| 可选依赖 | Jade ≥ 11 |
| 开发者 | terk |
| 许可证 | MIT |

安装时将构建产物放入 `mods` 目录，并确保 Touhou Little Maid 与 Cloth Config 已安装。Jade 不是必需依赖，仅用于在准星信息中显示自动工作状态。

---

## 一、快速开始

1. 安装 Touhou Little Maid、Cloth Config 与本模组。
2. 在游戏内通过配置界面或 `config/tlm_sincerely-common.toml` 调整功能。
3. 使用 `/tlmchat` 与附近女仆对话。
4. 在女仆 GUI 中打开自动工作页，配置任务顺序并开启女仆的自动切换。
5. 使用 `/tlmmemory` 或 AI 工具管理女仆记忆。
6. 若允许女仆执行命令，通过配置开启 `run_command` 工具；破坏性命令可配置为需要主人确认。

---

## 二、组织架构

模组按“接入层 — 功能层 — AI 层 — 数据层 — 客户端层”组织：

| 层级 | 职责 | 主要包位置 |
|---|---|---|
| 接入层 | 注册命令、事件、网络、TLM 扩展、任务数据 | `SincerelyMod`、`SincerelyExtension` |
| 对话层 | 聊天栏拦截、女仆查找、模式切换、改名 | `chatbar`、`command.ChatCommand` |
| 工作层 | 自动切换调度、优先级预设、检测结果、兼容报告 | `priority` |
| 记忆层 | 记忆存储、上下文注入、自动整理 | `memory` |
| 命令层 | 以主人身份执行命令、名单、确认、限流、审计 | `command`、`ai.tool.MaidCommandTool` |
| AI 层 | Context 注入与 Tool 调用 | `ai.context`、`ai.tool` |
| 客户端层 | GUI、本地预设库、快照缓存、Jade 提示 | `client` |
| 数据层 | TOML 配置、JSON 预设、JSON 记忆、审计日志 | `config`、`priority.autowork`、`memory` |

### 源码包结构

```text
tlm_sincerely/
├── SincerelyMod.java
├── SincerelyExtension.java
├── ai/
│   ├── context/
│   └── tool/
├── chatbar/
├── client/
│   ├── autowork/
│   ├── gui/
│   ├── network/
│   └── jade/
├── command/
├── config/
│   └── subconfig/
├── memory/
├── mixin/
└── priority/
    ├── autowork/
    │   ├── compat/
    │   ├── menu/
    │   ├── network/
    │   └── push/
    ├── decision/
    └── detection/
        └── compat/
```

---

## 三、功能详解

### 1. 聊天栏女仆对话

#### 玩家功能

- 在聊天栏直接与附近的女仆对话。
- 支持 `@名字` 前缀指定女仆；严格前缀模式开启后，只有带前缀的消息会发送给女仆。
- 无前缀时，可按配置范围自动选择最近的女仆。
- 支持全局可见与私聊模式：私聊模式下玩家消息与女仆回复仅相关玩家可见。模式与可见性按玩家独立生效，随玩家数据持久化。
- 女仆回复会通过气泡与聊天栏展示。
- 支持中文女仆名字、UUID 精确选择、同名女仆提醒与附近女仆列表。
- 支持通过命令给女仆改名。

#### 相关命令

| 命令 | 说明 |
|---|---|
| `/tlmchat <消息>` | 与最近女仆对话 |
| `/tlmchat to <名字> <消息>` | 与指定名字女仆对话 |
| `/tlmchat uuid <UUID> <消息>` | 与指定 UUID 女仆对话 |
| `/tlmchat list` | 列出附近女仆 |
| `/tlmchat mode` | 切换自己的女仆对话模式（按玩家独立生效） |
| `/tlmchat mode on` | 开启自己的对话模式 |
| `/tlmchat mode off` | 关闭自己的对话模式 |
| `/tlmchat global` | 切换自己的全局/私聊可见性偏好（按玩家独立生效） |
| `/tlmchat rename <名字或 uuid:UUID> <新名字>` | 改名，仅可操作自己的女仆 |

#### 配置项

配置段：`[chatbar]`

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `ChatModeEnabled` | `true` | 新玩家的女仆对话模式默认值；玩家可用 `/tlmchat mode` 单独覆盖 |
| `GlobalChatVisible` | `true` | 新玩家的全局/私聊可见性默认值；玩家可用 `/tlmchat global` 单独覆盖 |
| `RequirePrefix` | `true` | 是否强制要求 `@` 前缀 |
| `AutoChatRange` | `5.0` | 无前缀时自动对话范围，`0` 表示禁用 |
| `PrefixPattern` | `@` | 名字前缀字符 |

#### 当前边界

- 聊天栏 GUI 内的快捷按钮尚未提供。
- 聊天栏输入框内的 Tab 补全仅在命令环境中生效；`/tlmchat` 的参数补全可用。
- 女仆回复在多玩家环境下的广播行为需要在实机多人环境中验证。

### 2. 自动工作与任务优先级

#### 玩家功能

- 在女仆 GUI 中打开“自动工作”页，可：
  - 新建、删除、切换、重命名预设；
  - 将任务从“可用任务”加入“已排序任务”；
  - 调整已排序任务的顺序；
  - 移除任务。
- 该页没有独立的保存按钮：打开这只女仆的配置页时，增删任务、调整顺序与改名会**立刻**写入本机预设库并同时绑定到这只女仆；切换或新建预设同样立即生效。未打开该页的其他女仆不受影响。
- 女仆头像下方提供自动工作开关；全局调度关闭时，该开关仅显示状态，不会提交切换请求。
- 每个女仆保存自己的绑定快照：预设名、预设 UUID 与任务顺序。服务端调度只读取该快照。
- 玩家客户端拥有自己的预设库；服务端仅在启动时读取一次作为种子，之后不再代替客户端修改预设库。
- 玩家可把自己的预设推送给其他玩家；接收方确认后才会写入其客户端预设库，且不会自动改变任何女仆的绑定。
- 若安装 Jade，准星指向“已开启自动工作且全局调度开启”的女仆时，会与“主人：”“模式：”同列显示“处于工作自动切换模式”。

#### 自动切换行为

- 只有日程为 `WORK` 且女仆自身自动工作状态为启用的女仆会被调度。
- 任务顺序按列表顺序作为优先级，索引靠前的任务优先级更高。
- 切换前会检测目标是否真正可用，包括：
  - 任务是否注册、启用；
  - 是否符合兼容策略；
  - 是否有可检测的工作目标；
  - 需要硬性工具的任务是否持有对应工具。
- 部分任务允许从背包换到主手后再切换，例如钓鱼竿、灭火器、剪刀与攻击族武器。
- 骑乘（椅子、棋盘、船等）时仍会对全部绑定任务做可用性检测；要切到“不是当前这份骑乘工作”的任务前会先让女仆下车，当前骑乘工作仍可用时保持坐着。玩家 `shift+右键` 的坐下待命不受影响。
- 攻击族按任务家族校验武器：近战“攻击”**不会**把弓、弩、三叉戟、御币当作近战武器（身上只有弓时不会切近战）；弓兵需要弓与箭；弩、三叉戟、弹幕各自按原模组规则判定，且切换前会把武器换到主手。
- 攻击任务的检测距离与原模组该任务的实际反应距离一致：近战在跟随模式下按“主人到目标”的距离判断，弓兵按“女仆到目标”的距离判断，并且都要求视线可见。这样不会出现“检测到目标就切入、切过去却原地发呆”的情况。
- 正在进行中的任务受到繁忙保护，不会被普通切换打断；但该保护有最大时长，且任务被持续判定为不可用时会在有限时间后放行。
- 任务可用与不可用都需要连续确认，避免瞬时抖动。
- 存在反向切换抑制：在时间窗口内反复 A→B→A 会触发冷却。
- 当女仆已自动切入某任务，但任务持续可用且 Brain 长时间没有工作目标时，可强制刷新一次 Brain。
- 实验性攻击抢占默认关闭；开启后，配置中的攻击任务可以在不等待普通保持时间时抢占当前任务，但仍然需要通过武器与目标检测。
- 全局开关只暂停调度，不会清除女仆状态、缓存或真实任务；重新启用会从现有状态继续。
- `idle` 是系统兜底任务，不是可配置的工作目标，永远不会被自动切入。
- 开启“强制启用预选工作”后，兼容黑名单不再阻止自动切换，但任务仍必须被检测器判定为可用；`idle` 与没有检测器的任务依旧永不切入。开启期间玩家登录会收到一条额外警告。

#### 预设推送

- 推送是“提议—确认”流程：
  1. 发送方从自己的客户端预设库选择预设；
  2. 服务端创建一个待确认提议；
  3. 接收方在聊天栏看到提议后点击接受或拒绝；
  4. 接受后，预设才会写入接收方的客户端预设库。
- 推送不会直接修改任何女仆的绑定快照。
- 广播推送需要发送方拥有等级 2 及以上权限。
- 待确认提议存在超时，接收方下线会清理对应提议。

#### 兼容报告

使用 `/tlmautowork compat` 查看当前服务器注册任务的兼容情况：

- `SUPPORTED`：存在专用检测器。
- `FALLBACK`：通过通用接口检测，例如攻击族或农耕族。
- `UNSUPPORTED`：没有可靠检测器，或该任务语义不适合自动切换。
- `BLOCKED`：被服务器名单阻止。
- `cook` 属于部分支持：仅支持炉灶相关子任务，其他设备保持不可用；该任务会持续显示在问题视图中。

报告中的问题视图包含被阻止任务、未加白名单的不支持任务，以及部分支持任务。服务器可在登录时按提醒等级向玩家发送摘要；把 `[multi_task] DisableCompatReminder` 打开可关闭该提醒，手动执行 `/tlmautowork compat report` 仍可用。

### 3. 简易记忆系统

#### 玩家功能

- 每个女仆拥有独立的键值对记忆。
- 记忆分为两类：
  - 核心记忆：少量重要记忆，全文注入 AI 上下文；
  - 归档记忆：其余记忆，默认仅注入键名与截断预览。
- 女仆可通过 AI 工具自主记住、回忆、遗忘、搜索与合并记忆。
- 记忆系统会在阈值触发时自动整理归档记忆；整理期间相关对话出口会被静默，AI 工具仅允许查询、回忆与合并。
- 玩家可通过命令查看、导出、删除记忆，或触发 AI 回顾补写。
- 记忆文件按女仆 UUID 持久化，服务端重启后保留。
- 女仆经神龛/胶片复活后记忆与名字自动延续（按胶片快照迁移；祭坛配方复活暂不覆盖）。

#### 相关命令

| 命令 | 说明 |
|---|---|
| `/tlmmemory set <名字> <key> <value>` | 设置归档记忆 |
| `/tlmmemory set-core <名字> <key> <value>` | 设置核心记忆 |
| `/tlmmemory get <名字> <key>` | 查看记忆详情 |
| `/tlmmemory list <名字>` | 列出全部记忆 |
| `/tlmmemory forget <名字> <key>` | 删除记忆 |
| `/tlmmemory export <名字> [json/text/context]` | 导出记忆 |
| `/tlmmemory summarize <名字>` | 触发 AI 回顾并补写遗漏 |

#### 配置项

配置段：`[memory]`

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `Enabled` | `true` | 记忆系统总开关 |
| `MaxMemories` | `50` | 每个女仆最大记忆数 |
| `CoreMemoryLimit` | `10` | 核心记忆全文注入上限 |
| `ContextPreviewLength` | `30` | 归档记忆截断长度 |
| `AutoEvict` | `true` | 满容时自动淘汰最旧归档记忆 |
| `MemoryGuidance` | `true` | 向 LLM 注入记忆引导 |
| `TidyEnabled` | `true` | 自动整理开关 |
| `TidyThreshold` | `0.8` | 整理触发阈值 |
| `TidyCooldownMinutes` | `20` | 整理冷却分钟数 |
| `ShowSource` | `false` | 是否显示记忆来源玩家 |
| `PreviewMode` | `full` | 归档预览模式：`full` 或 `keys_only` |

#### 当前边界

- 没有记忆 GUI。
- 没有日记本物品。
- 不支持女仆之间共享记忆。
- 搜索为关键词子串匹配，不是语义搜索。
- 自动整理期间会按女仆维度静默对话出口；若同时有其他来源的对话，可能受到附带影响。
- 记忆来源在工具层使用女仆主人名，多人共享场景下不能精确区分实际对话发起者。

### 4. 女仆命令执行工具

#### 玩家功能

- 允许女仆把主人的自然语言意图翻译为一条 Minecraft 命令。
- 命令以主人身份执行，位置、维度、朝向、`@s` 与相对坐标均按主人上下文处理。
- 权限上限只能下调主人真实权限，不会提升。
- 黑名单命令始终拒绝，且不会进入确认流程。
- 确认名单中的命令在开启确认功能后，需要主人点击可点击的确认按钮。
- 主人可选择“本次游戏内不再确认”；该信任仅在当前登录会话内有效，主人登出或服务端重启后清空。
- 挂起确认期间，该女仆的新对话会被拒绝。
- 实际执行的命令会回显主人聊天栏。
- 命令审计日志记录决策、命令名、时间与相关女仆/主人，不记录完整输出。

#### 相关命令

| 命令 | 说明 |
|---|---|
| `/tlmconfirm run <token>` | 确认并执行挂起命令 |
| `/tlmconfirm cancel <token>` | 取消挂起命令 |
| `/tlmconfirm trust <token>` | 确认执行，并在本次登录会话内信任该类根命令 |

确认按钮通常会直接调用上述命令；`token` 与主人绑定，其他玩家无法代替确认。

#### 配置项

配置段：`[maid_command]`

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `CommandToolEnabled` | `true` | 是否向女仆提供 `run_command` 工具 |
| `ConfirmationEnabled` | `false` | 确认名单中的命令是否需要主人确认 |
| `SessionConfirmationEnabled` | `true` | 是否允许“本次游戏内不再确认” |
| `ConfirmationTimeoutSeconds` | `60` | 确认超时秒数 |
| `ConfirmationRequiredCommands` | 见配置文件 | 需要确认的根命令名单 |
| `BlacklistedCommands` | 见配置文件 | 永久拒绝的根命令名单 |
| `MaxPermissionLevel` | `4` | 主人权限上限，只下调不提升 |
| `MaxCommandsPerRequest` | `3` | 单次对话链最大命令尝试次数 |
| `MaxCommandLength` | `1024` | 最大命令长度 |
| `ToolResultMaxChars` | `2000` | 回传给模型的结果截断长度 |
| `AuditLogMaxSizeMb` | `16` | 审计日志轮转上限 |

#### 当前边界

- 名单是缓解措施，不是沙箱；无法穷举所有模组命令、别名或别名包装。
- 命令执行与确认都要求主人在线。
- 名单粒度为根命令，不支持子命令或参数级控制。
- 命令输出不进行分页处理。

---

## 四、注册命令总表

| 命令 | 权限要求 | 运行侧 | 说明 |
|---|---|---|---|
| `/tlmchat` | 玩家 | 服务端 | 与附近女仆对话 |
| `/tlmchat to` | 玩家 | 服务端 | 按名字选择女仆 |
| `/tlmchat uuid` | 玩家 | 服务端 | 按 UUID 选择女仆 |
| `/tlmchat list` | 玩家 | 服务端 | 列出附近女仆 |
| `/tlmchat mode` | 玩家 | 服务端 | 切换自己的对话模式（按玩家生效） |
| `/tlmchat global` | 玩家 | 服务端 | 切换自己的全局/私聊偏好（按玩家生效） |
| `/tlmchat rename` | 仅女仆主人 | 服务端 | 女仆改名 |
| `/tlmmemory` | 仅女仆主人 | 服务端 | 记忆管理 |
| `/tlmautowork compat report` | 玩家 | 服务端 | 查看问题任务 |
| `/tlmautowork compat report all` | 玩家 | 服务端 | 查看全部任务 |
| `/tlmautowork compat supported` | 玩家 | 服务端 | 查看专用检测任务 |
| `/tlmautowork compat fallback` | 玩家 | 服务端 | 查看通用接口任务 |
| `/tlmautowork compat unsupported` | 玩家 | 服务端 | 查看不支持任务 |
| `/tlmautowork compat blocked` | 玩家 | 服务端 | 查看被阻止任务 |
| `/tlmautowork compat blacklist` | 等级 2 | 服务端 | 黑名单增删查 |
| `/tlmautowork compat whitelist` | 等级 2 | 服务端 | 白名单增删查 |
| `/tlmautowork compat set reminder` | 等级 2 | 服务端 | 登录提醒等级 |
| `/tlmautowork compat reload` | 等级 2 | 服务端 | 重载兼容配置 |
| `/tlmautowork preset send` | 玩家；广播需等级 2 | 客户端命令 | 推送单个预设 |
| `/tlmautowork preset sendlibrary` | 玩家；广播需等级 2 | 客户端命令 | 推送全部预设库 |
| `/tlmautowork preset accept` | 目标玩家 | 服务端 | 接受预设推送 |
| `/tlmautowork preset reject` | 目标玩家 | 服务端 | 拒绝预设推送 |
| `/tlmconfirm` | token 对应主人 | 服务端 | 命令确认流程 |

> `/tlmautowork preset send` 与 `/tlmautowork preset sendlibrary` 在客户端注册。未识别的客户端命令路径会回退到服务端命令分发器，因此 `compat`、`accept`、`reject` 等子命令仍然可用。

---

## 五、AI 能力注册

### Context

| ID | 说明 |
|---|---|
| `tlm_sincerely_memory` | 女仆持久化记忆：关于玩家、历史事件与偏好的键值事实 |
| `tlm_sincerely_auto_work` | 当前自动工作状态与绑定任务顺序 |

### Tool

| Tool ID | 可用 action | 说明 |
|---|---|---|
| `auto_work` | `query`、`set_auto_enabled`、`add_task`、`remove_task`、`move_task` | 查询并编辑当前女仆的绑定任务顺序 |
| `tlm_memory` | `remember`、`recall`、`forget`、`search`、`merge` | 女仆自主管理记忆 |
| `run_command` | 由 LLM 生成命令 | 以主人身份执行 Minecraft 命令 |

`auto_work` 不能创建、选择、重命名或删除预设：预设库属于玩家客户端，服务端不可见。工具会明确拒绝这些操作，并引导玩家在女仆 GUI 中完成。旧的数字优先级 `set` 语义已不再支持。

---

## 六、兼容工作模式

### 兼容等级

| 等级 | 含义 | 是否自动调度 |
|---|---|---|
| `SUPPORTED` | 存在专用检测器 | 允许 |
| `FALLBACK` | 通过通用任务接口检测 | 允许 |
| `UNSUPPORTED` | 无可靠检测器或任务不适合自动切换 | 不允许 |
| `PARTIAL_SUPPORT` | 仅支持已验证子集 | 允许，但持续展示在问题视图中 |
| `BLOCKED` | 被服务器名单阻止 | 不允许 |

### 当前兼容矩阵

| 来源 | 任务 UID | 兼容等级 | 说明 |
|---|---|---|---|
| Touhou Little Maid | `touhou_little_maid:honey` | `SUPPORTED` | 成熟蜂巢、产物空间与可达性 |
| Touhou Little Maid | `touhou_little_maid:feed` | `SUPPORTED` | 主人状态与公开食物任务优先级 |
| Touhou Little Maid | `touhou_little_maid:milk` | `SUPPORTED` | 空桶、背包空间与可达成年牛 |
| Touhou Little Maid | `touhou_little_maid:feed_animal` | `SUPPORTED` | 专用良性喂养检测，覆盖错误的攻击回退 |
| Touhou Little Maid | `touhou_little_maid:torch` | `SUPPORTED` | 亮度、放置面、火把与路径可达性 |
| Touhou Little Maid | `touhou_little_maid:fishing` | `SUPPORTED` | 需要鱼竿与可用水域/座位；切入前自动换到主手 |
| Touhou Little Maid | `touhou_little_maid:extinguishing` | `SUPPORTED` | 灭火器持有与生物着火目标；切入前自动换到主手 |
| Touhou Little Maid | `touhou_little_maid:shears` | `SUPPORTED` | 剪刀与可剪切实体；切入前自动换到主手 |
| Touhou Little Maid | `touhou_little_maid:board_games` | `SUPPORTED` | 未占用或当前女仆正在使用的棋盘 POI |
| Touhou Little Maid | `touhou_little_maid:attack` | `FALLBACK` | 近战武器与攻击目标 |
| Touhou Little Maid | `touhou_little_maid:ranged_attack` | `FALLBACK` | 弓与对应弹药 |
| Touhou Little Maid | `touhou_little_maid:crossbow_attack` | `FALLBACK` | 弩 |
| Touhou Little Maid | `touhou_little_maid:trident_attack` | `FALLBACK` | 三叉戟 |
| Touhou Little Maid | `touhou_little_maid:danmaku_attack` | `FALLBACK` | 御币 |
| Touhou Little Maid | `touhou_little_maid:idle` | 排除 | 系统兜底任务，不参与报告计数与自动调度 |
| 任意模组 | 实现 `IAttackTask` 的任务 | `FALLBACK` | 使用任务自身的武器判定；第三方攻击任务保持其原有契约 |
| 任意模组 | 实现 `IFarmTask` 的任务 | `FALLBACK` | 通用农耕检测，按基准点单点判可达，受区块与路径预算限制 |
| Touhou Little Maid | `touhou_little_maid:cocoa` / `melon` | `FALLBACK` | 通用农耕检测；这两个任务按“基准点周围 3×2×3 任一格可达”判定，与主模组一致 |
| MaidSoulKitchen | `maidsoulkitchen:berries_farm` | `SUPPORTED` | 需要公共 `ICompatFarmTask` 相关类存在；可达性按浆果丛周围 3×2×3 判定 |
| MaidSoulKitchen | `maidsoulkitchen:fruit_farm` | `SUPPORTED` | 按女仆 Handler 与成熟水果扫描；可达性判在果实下方基准点 |
| MaidSoulKitchen | `maidsoulkitchen:feed_animal_t` | `SUPPORTED` | 专用繁殖/清理检测，覆盖错误的攻击回退 |
| MaidSoulKitchen | `maidsoulkitchen:cook` | `PARTIAL_SUPPORT` | 仅支持炉灶设备；其他设备保持不可用 |
| Maid Useful Task | `maid_useful_task:maid_tree` | `SUPPORTED` | 只读自然树近似与可达性检测 |
| Maid Useful Task | `maid_useful_task:locate` | `UNSUPPORTED` | 本质是跟随主人的寻路任务，不适合自动切换 |
| Maid Useful Task | `maid_useful_task:revive_player` | `UNSUPPORTED` | 由玩家死亡事件驱动，没有稳定世界扫描语义 |
| Maid Storage Manager | `maid_storage_manager:storage_manage` | `SUPPORTED` | 支持 PLACE/RESORT；REQUEST、CO_WORK 与未核验状态保持未知 |
| 其他未适配任务 | 任意 UID | `UNSUPPORTED` | 无检测器时不自动切入，但可通过白名单显示在报告中 |

> Maid Storage Manager 的实测基线为 1.15.6。更旧的 1.4.1 版本与 Touhou Little Maid 1.5.3 存在不兼容，女仆切入 PLACE 时可能崩服；请使用 1.15.x 或更新版本。

### 兼容配置

配置文件：`config/tlm_sincerely/auto_work_compat.json`

| 字段 | 默认值 | 说明 |
|---|---|---|
| `blacklist` | 空 | 阻止自动调度的任务 UID |
| `whitelist` | 空 | 显示在报告中但不改变调度权限的任务 UID |
| `knownBadFallback` | 空 | 已知不可靠的通用回退任务 UID |
| `reminderLevel` | `OP_ONLY` | 登录提醒等级：`ALL`、`OP_ONLY`、`DISABLED` |

内置的 `knownBadFallback` 包含旧版本中错误的攻击回退任务；当对应专用检测器注册后，专用检测优先，任务按 `SUPPORTED` 处理。

---

## 七、配置与数据文件

| 文件 | 说明 |
|---|---|
| `config/tlm_sincerely-common.toml` | 主配置文件，可通过 Cloth Config 或 Configured 编辑 |
| `config/tlm_sincerely/auto_work_presets.json` | 自动工作预设；服务端只读种子，客户端可写 |
| `config/tlm_sincerely/auto_work_compat.json` | 自动工作兼容名单与提醒等级 |
| `config/tlm_sincerely/maid_memories/<uuid>.json` | 按女仆 UUID 存储的记忆文件 |
| `logs/tlm_sincerely/command_audit.log` | 命令执行审计日志 |

### 自动工作配置

配置段：`[multi_task]`

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `Enabled` | `true` | 全局自动调度开关；关闭只暂停调度，不清理状态 |
| `PollInterval` | `100` | 调度轮询间隔，单位 tick |
| `ExperimentalAttackPreempt` | `false` | 实验性攻击抢占 |
| `ForceEnablePreselected` | `false` | 实验性绕过兼容黑名单，但仍需检测器报告可用 |
| `DisableCompatReminder` | `false` | 关闭进服的工作兼容聊天提醒；关闭时沿用提醒等级设置 |
| `ForceBrainRefreshOnStuck` | `true` | 任务可用但无目标时强制刷新 Brain |
| `AvailableConfirmations` | `1` | 连续可用确认次数 |
| `UnavailableConfirmations` | `2` | 连续不可用确认次数 |
| `MinimumTaskHoldTicks` | `60` | 普通任务最短保持时间 |
| `ReverseSwitchWindowTicks` | `240` | 反向切换统计窗口 |
| `ReverseSwitchThreshold` | `2` | 反向切换阈值 |
| `ReverseSwitchCooldownTicks` | `200` | 反向切换冷却 |
| `BusyGuardEnabled` | `true` | 繁忙保护 |
| `BusyIdleForgiveTicks` | `60` | 繁忙状态宽限时间 |
| `BusyUnavailableHoldTicks` | `80` | 不可用任务的额外保持时间 |
| `BusyGuardMaxTicks` | `600` | 单次繁忙保护最大时长 |
| `DetectionBlockBudgetPerTick` | `256` | 每服务器 tick 农耕方块检测预算 |
| `PathCheckBudgetPerTick` | `4` | 每服务器 tick 路径可达性检测预算 |

---

## 八、开发者代码索引

> 以下是当前功能的源码入口与文档入口。具体实现细节请查看对应源码包与详细文档；本 Wiki 不重复记录实现细节。

### 入口与注册

| 功能 | 入口 |
|---|---|
| Forge 生命周期、命令、网络、服务绑定 | `SincerelyMod.java` |
| TLM 附属扩展、AI Tool、Context、任务数据 | `SincerelyExtension.java` |
| 兼容检测器引导 | `priority/detection/compat/CompatDetectorBootstrap.java` |
| 网络通道注册 | `priority/autowork/network/AutoWorkNetworking.java` |
| Mixin 配置 | `src/main/resources/tlm_sincerely.mixins.json` |

### 功能模块索引

| 模块 | 主要包/类 | 详细文档 |
|---|---|---|
| 聊天栏对话 | `chatbar/`、`command/ChatCommand.java` | [docs/聊天栏女仆对话模块.md](docs/聊天栏女仆对话模块.md) |
| 女仆查找 | `chatbar/MaidFinder.java` | [docs/聊天栏女仆对话模块.md](docs/聊天栏女仆对话模块.md) |
| 自动切换调度 | `priority/TaskAutoSwitchHandler.java`、`priority/decision/` | [docs/自动切换工作模块.md](docs/自动切换工作模块.md) |
| 预设与绑定快照 | `priority/autowork/` | [docs/自动切换工作模块.md](docs/自动切换工作模块.md) |
| 工作检测 | `priority/detection/` | [docs/自动切换工作模块.md](docs/自动切换工作模块.md) |
| 兼容报告 | `priority/autowork/compat/` | [docs/自动切换工作模块.md](docs/自动切换工作模块.md) |
| 自动工作 GUI | `client/gui/autowork/` | [docs/自动切换工作模块.md](docs/自动切换工作模块.md) |
| 客户端预设库 | `client/autowork/` | [docs/自动切换工作模块.md](docs/自动切换工作模块.md) |
| 记忆系统 | `memory/`、`ai/tool/MaidMemoryTool.java`、`ai/context/MaidMemoryContext.java` | [docs/简易记忆系统模块.md](docs/简易记忆系统模块.md) |
| 命令执行 | `command/`、`ai/tool/MaidCommandTool.java` | [docs/女仆命令执行模块.md](docs/女仆命令执行模块.md) |
| 配置系统 | `config/`、`client/gui/ConfigScreen.java` | 无单独文档 |
| 客户端集成 | `client/network/`、`client/jade/`、`mixin/` | 无单独文档 |

### GUI 与客户端入口

| 功能 | 入口 |
|---|---|
| 女仆 GUI 自动工作页 | `client/gui/autowork/AutoWorkConfigScreen.java` |
| 自动工作开关按钮 | `client/gui/autowork/AutoWorkVirtualTaskButton.java` |
| 侧边栏按钮 | `client/gui/autowork/AutoWorkMaidTabButton.java` |
| 本地预设库 | `client/autowork/AutoWorkClientLibrary.java` |
| 推送命令 | `client/autowork/AutoWorkPushCommand.java` |
| 快照缓存 | `client/network/ClientAutoWorkService.java` |
| Jade 提示 | `client/jade/AutoWorkJadeProvider.java` |

---

## 九、常见边界与注意事项

- 本模组不会提升玩家权限；命令执行工具只能以主人现有权限上限运行命令。
- 自动工作只调度已注册且已启用的任务；未适配任务不会被强行切入。
- 女仆的自动工作状态与真实任务相互独立：关闭自动工作不会把女仆强行切回 `idle`。
- 预设库是客户端私有数据；服务端只保留启动时的只读种子与每个女仆的绑定快照。
- 推送预设只写入接收方的预设库，不会自动改变任何女仆当前绑定。
- 只有“打开某只女仆的自动工作页并改动其预设”“重新选择预设”“新建预设”会改写该女仆的绑定；编辑其他预设、推送入库、重启游戏都不会自动改已绑定女仆。
- 记忆整理期间会静默相关对话出口，这是预期行为，用于避免整理过程被玩家看到。
- 命令确认 token 与主人绑定；不要把 token 分享给其他玩家。
- 若需要排查兼容问题，优先使用 `/tlmautowork compat report`，而不是直接修改预设。
