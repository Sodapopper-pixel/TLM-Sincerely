# TLM Sincerely - 开发计划

> 本文档不写入具体的代码实现，而是记录已实现部分、未来规划等内容。

## 项目信息

- **模组名称**：《车万女仆：真心为你》
- **Mod ID**: `tlm_sincerely`
- **版本**: 0.1.0 (Forge 1.20.1)
- **主模组依赖**: touhou_little_maid ≥ 1.5.1
- **开发者**: terk
- **最近更新日期**：2026-08-02

---

## 本文档更新规范

1. **更新条件**：只有在用户明确说明下才能更新本计划
2. **内容原则**：精简内容，以已实现和待实现为主，减少代码内容写入
3. **任务大纲**：大功能 → 小功能 → 细则

---

## 已实现与待实现功能

### 一、聊天栏女仆对话（基本实现）

- `docs/聊天栏女仆对话模块.md` - 聊天栏女仆对话功能开发文档（架构、数据流、Mixin 细节）

#### 1. 基础对话命令
- ✓ `/tlmchat <消息>` - 与最近女仆对话
- ✓ `/tlmchat to <名字> <消息>` - 与指定名字女仆对话
- ✓ `/tlmchat uuid <UUID> <消息>` - 与指定 UUID 女仆对话
- ✓ `/tlmchat list` - 显示附近女仆列表
- ✓ Tab 补全（女仆名字、UUID，仅命令环境）

#### 2. 模式切换命令
- ✓ `/tlmchat mode` - 切换女仆对话模式
- ✓ `/tlmchat mode on/off` - 开启/关闭女仆对话模式
- ✓ `/tlmchat global` - 切换全局/私聊模式

#### 3. 聊天栏对话（ServerChatEvent）
- ✓ 聊天栏输入自动拦截（需开启女仆对话模式）
- ✓ `@名字` 前缀始终解析（不依赖严格前缀模式开关）
- ✓ 严格前缀模式：开启后仅 @名字 消息发给女仆
- ✓ 自动对话范围：无前缀时自动匹配最近女仆
- ✓ 全局/私聊控制：玩家消息广播/隐藏
- ✓ 女仆回复全局广播（Mixin `ChatBubbleManager.addLLMChatText`）

#### 4. 女仆查找逻辑
- ✓ MaidFinder - 女仆查找算法（模糊匹配、距离优先）
- ✓ 同名女仆提醒（黄色提示 + UUID 截断）

#### 5. 配置系统
- ✓ 主模组风格 subconfig 分类（ChatBarConfig → GeneralConfig）
- ✓ Cloth Config GUI（ConfigScreen）
- ✓ 配置项：女仆对话模式、全局聊天可见、严格前缀模式、自动对话范围、前缀字符

#### 6. 国际化
- ✓ zh_cn / en_us 完整语言文件

#### 7. 已知限制
- ⚠ 聊天栏 Tab 补全仅命令环境生效（需 Mixin ChatScreen）
- ⚠ 女仆回复广播需多玩家环境验证（单人无感知差异）
- ⚠ 聊天栏 GUI 按钮暂未实现（方案待定）

#### 8. 聊天栏 GUI 按钮（待实现）
- ⏸ Mixin ChatScreen 注入按钮
- ⏸ 备选方案：按键绑定 / Overlay HUD

### 二、多工作模式（已实现，2026-08-15 v3 兼容升级）

- `docs/自动切换工作模块.md` - 多工作模式开发文档（检测架构、切换规则、配置格式）
- `docs/plans/独立工作检测系统重构计划.md` - 已完成的独立检测系统重构计划归档
- `docs/plans/自动工作稳定性与附属兼容计划.md` - v3 收尾，详见下文

#### 1. 数据层
- ✓ TaskPriorityPreset - 预设数据类（优先级映射 + 排序列表）
- ✓ TaskPriorityManager - JSON 持久化管理（CRUD、序列化/反序列化）
- ✓ 配置文件：`config/tlm_sincerely/task_priority_presets.json`

#### 2. 自动切换
- ✓ TaskAutoSwitchHandler - Forge ServerTickEvent 调度入口，仅处理 `Activity.WORK` 女仆
- ✓ TaskWorkDetectorRegistry - 按 UID / 接口注册只读工作检测器，未适配任务返回 `UNKNOWN`
- ✓ AttackTaskWorkDetector - `IAttackTask.findFirstValidAttackTarget()` 独立检测
- ✓ FarmTaskWorkDetector - `IFarmTask` 增量扫描、种子快照、方块与路径预算
- ✓ MaidDetectionCache / TaskDetectionRuntimeState - 按服务器会话隔离缓存、游标和生命周期清理
- ✓ TaskSwitchDecisionEngine - 唯一自动 `maid.setTask()` 调用点，确认次数、最短保持与 v3 平衡 UNKNOWN 接管语义
- ✓ 实验性攻击抢占默认关闭，仅允许预设中已配置的攻击任务立即抢占
- ✓ 自动切换后任务仍为 AVAILABLE 但连续 60 tick 无工作目标时，可按默认开启的配置强制刷新一次 Brain
- ✓ 旧 Probe 试切、Brain idle 判断、Memory 清理逻辑及旧配置字段已删除
- ✓ TaskScanCursor 不再因女仆移动重置扫描；PATH_BUDGET_EXHAUSTED 推进到下一候选
- ✓ 成功切换记录 INFO 级 `[TaskDecision]`，reason 区分 `EXTERNAL_UNKNOWN_REPLACED` / `HIGHER_PRIORITY_OVER_UNKNOWN` / `CURRENT_UNSUPPORTED_OR_DISABLED` / `CURRENT_AVAILABLE` / `CURRENT_UNAVAILABLE_CONFIRMING` 等

#### 3. GUI 层
- ✓ TaskPriorityScreen - 独立 Screen（非 Container）
- ✓ 侧边栏入口（MaidSideTabsMixin 注入 index=2）
- ✓ 双列布局：左侧未排序，右侧已排序
- ✓ 顶部两行控件：[总开关] [预设名] [<] [>] / [新建] [删除]
- ✓ 滚轮修改优先级数字（1-10 循环）
- ✓ ▲▼ 按钮调整同优先级内顺序
- ✓ 右键移除任务
- ✓ 实时保存，无需手动保存
- ✓ 自定义侧边栏按钮（PrioritySideTabButton + 独立纹理）

#### 4. AI Tool
- ✓ TaskPriorityTool - 实现 ITool<Result>
- ✓ 支持 query / set / switch_preset 三个 action

#### 5. 配置系统
- ✓ PriorityConfig - 检测确认、最短保持、检测预算、阻塞 Brain 刷新与实验性攻击抢占（配置段：`[multi_task]`）
- ✓ 默认关闭多工作模式
- ✓ 界面总开关实时切换

#### 6. 国际化
- ✓ zh_cn / en_us 完整语言文件

#### 7. 已知问题
- ⚠ 滚轮修改优先级时，焦点与光标所在行可能不同步（GUI 缩放导致坐标偏差）

#### 8. 附属任务兼容（v3 已实现）
- ✓ TLM 8 个专用 Detector：`honey`/`feed`/`milk`/`feed_animal`/`torch`/`fishing`/`extinguishing`（在 `Builtin*`） + 已有 `shears`/`board_games`
- ✓ MaidSoulKitchen 4 个 Detector：`berries_farm`/`fruit_farm`/`feed_animal_t`/`cook`（仅炉灶子任务）
- ✓ MaidUsefulTask 2 个 Detector：`maid_tree`（只读自然树近似）+ `locate`（只认主手+目标验证）
- ✓ MaidStorageManager Detector：`storage_manage`（PLACE/RESORT + 可达 ITEM_HANDLER）
- ✓ known-bad fallback：`touhou_little_maid:feed_animal` / `maidsoulkitchen:feed_animal_t`（错误攻击 fallback）
- ✓ `/tlmautowork compat report` 命令及子命令（`report all` / `supported` / `fallback` / `unsupported` / `blocked` / `blacklist` / `whitelist` / `set reminder` / `reload`）
- ✓ 报告顶部 4 个分类计数可点击切换；上一页/下一页携带当前 filter
- ✓ `touhou_little_maid:idle` 从报告与计数中排除，但 UID 仍用于配置校验，`isAutoScheduleAllowed(idle)` 恒为 false

---

### 三、简易记忆系统

- `docs/简易记忆系统模块.md` - 简易记忆系统功能与命令说明
- `docs/plans/简易记忆系统v2计划.md` - v2 强化计划（M-2.0 ~ M-2.8）

为女仆提供持久化的键值对记忆存储，让女仆能"记住"玩家的偏好、历史事件和个人信息。

**架构**：三层接口 + JSON 持久化 + Mixin 引导注入 + 后台自动整理
- Context 层：记忆索引自动注入 AI 上下文（核心记忆全文 + 归档记忆预览，支持 keys-only 瘦身）
- Tool 层：AI 可自主调用 `tlm_memory` 读写遗忘搜索合并记忆
- Mixin 层：system 消息注入引导（替代 Skill，消除触发悖论）
- 维护层：阈值触发后台自动整理，全出口静默，history 快照清理
- 命令层：玩家通过 `/tlmmemory` 管理记忆
- 持久化：`config/tlm_sincerely/maid_memories/<uuid>.json`（含 meta.lastTidyAt）

#### 1. AI 上下文注入
- ✓ 注册 `tlm_sincerely_memory` 分类（promptContext=true）
- ✓ 核心记忆（≤ 10 条）全文注入，归档记忆仅注入 key + 截断预览
- ✓ 关闭记忆系统时不注入
- ✓ `PreviewMode` 支持 `full` / `keys_only` 瘦身模式（M-2.8）
- ✓ `ShowSource` 可选在预览中显示来源玩家名（M-2.7）

#### 2. AI Tool（`tlm_memory`）
- ✓ `remember(key, value, importance)` - 写入记忆（重要性 core/archive）
- ✓ `recall(key)` - 按 key 获取完整记忆值（M-2.3: 更新访问统计）
- ✓ `forget(key)` - 删除指定记忆
- ✓ `search(query)` - 关键词子串搜索（M-2.1: 匹配 key/value，最多 10 条）
- ✓ `merge(keys, key, value)` - 合并 2-5 个 archive 条目（M-2.5: core 硬约束保护）
- ✓ 满容自动淘汰最旧 archive（M-2.2: `AutoEvict` 配置可回退旧拒绝行为）
- ✓ 维护模式期间 action 收窄（仅 search/recall/merge）
- ✓ 记忆满时返回容量提示

#### 3. 引导注入（M-2.4: 移除 Skill，改用 Mixin system 注入）
- ✓ `MemoryGuidanceMixin` 注入引导 system 消息（`@Redirect` on `buildMessage`）
- ✓ 引导文案作为常量冻结，每请求 O(1) 注入
- ✓ `summarize` 提示词改为自包含（不再引用 skill）

#### 4. 后台自动整理（M-2.6）
- ✓ 阈值触发（`TidyThreshold` × `MaxMemories`）+ 冷却（`TidyCooldownMinutes`）
- ✓ 全出口静默：气泡（`ChatBubbleSilenceMixin`）/ TTS（`TtsSilenceMixin`）/ 聊天栏（`MaidChatBroadcastMixin`）
- ✓ 维护轮 Tool action 收窄
- ✓ history 快照清理（维护前后 size 差值 pollLast）
- ✓ `lastTidyAt` 持久化到 JSON `meta` 对象

#### 4. 命令系统
- ✓ `/tlmmemory set <名字> <key> <value>` — 设置归档记忆
- ✓ `/tlmmemory set-core <名字> <key> <value>` — 设置核心记忆
- ✓ `/tlmmemory get <名字> <key>` — 查看记忆详情
- ✓ `/tlmmemory list <名字>` — 列出全部记忆
- ✓ `/tlmmemory forget <名字> <key>` — 删除记忆
- ✓ `/tlmmemory export <名字> [json|text|context]` — 导出记忆
- ✓ `/tlmmemory summarize <名字>` — 触发 AI 回顾对话补写遗漏
- ✓ Tab 补全女仆名字
- ✓ 支持中文女仆名字（自建 `UnicodeWordArgument` 参数类型）
- ✓ uuid:UUID 格式精确选择女仆

#### 5. 配置系统
- ✓ `[memory]` 配置段（Cloth Config GUI）
- ✓ `Enabled` — 记忆系统总开关（默认开）
- ✓ `MaxMemories` — 每只女仆最大记忆数（默认 50，范围 1-200）
- ✓ `CoreMemoryLimit` — 核心记忆全文注入上限（默认 10，范围 0-50）
- ✓ `ContextPreviewLength` — 归档记忆截断长度（默认 30，范围 10-200）
- ✓ `AutoEvict` - 满容自动淘汰（M-2.2, 默认 true）
- ✓ `MemoryGuidance` - 注入记忆引导（M-2.4, 默认 true）
- ✓ `TidyEnabled` - 自动整理开关（M-2.6, 默认 true）
- ✓ `TidyThreshold` - 整理触发阈值（M-2.6, 默认 0.8, 范围 0.5-1.0）
- ✓ `TidyCooldownMinutes` - 整理冷却（M-2.6, 默认 20, 范围 1-1440）
- ✓ `ShowSource` - 显示记忆来源（M-2.7, 默认 false）
- ✓ `PreviewMode` - 预览模式 full/keys_only（M-2.8, 默认 full）

#### 6. 国际化
- ✓ zh_cn / en_us 完整语言文件

#### 7. 技术备注
- ✓ 自定义 `UnicodeWordArgument` 解决 MC 1.20.1 Brigadier 1.1.8 不支持 Unicode 字符的问题
- ✓ 通过 `ArgumentTypeInfos.registerByClass()` + `SingletonArgumentInfo` 注册序列化器
- ✓ `set` / `set-core` 拆分为独立子命令，避免 Brigadier 中 `greedyString` + `literal` 同层歧义

#### 8. 已知限制
- ⚠ 无 GUI 管理界面（计划后续仿照 TaskPriorityScreen 实现）
- ⚠ 无日记本物品（计划后续作为独立特性）
- ⚠ 无女仆间记忆共享（属于后续"女仆间交流系统"范畴）
- ⚠ 记忆搜索为子串匹配，不支持自然语言语义搜索或 embedding
- ⚠ 后台维护轮的 history 清理基于 size 快照差值，维护期间若有其他来源对话可能误删
- ⚠ 维护轮静默期间，玩家新对话也会被静默（维护标记按 maid UUID 全局生效）
- ⚠ MemoryEntry.source 在 Tool 层使用 maid 主人名（非对话发起者），多人共享时不够精确

---

### 四、女仆间交流系统

- 待规划

---

### 五、预设人格

- 待规划

---

### 六、性格系统

- 待规划

---

### 七、女仆成长系统

- 待规划

---

## 技术备注

### 配置文件位置
- 客户端：`config/tlm_sincerely-common.toml`
- 使用 Configured 模组 GUI 编辑

### 命令参考
```
/tlmchat mode          # 切换女仆对话模式
/tlmchat mode on       # 开启
/tlmchat mode off      # 关闭
/tlmchat global        # 切换全局/私聊
/tlmchat <消息>        # 与最近女仆对话
/tlmchat to <名字> <消息>
/tlmchat uuid <UUID> <消息>
/tlmchat list          # 显示女仆列表

/tlmmemory set <名字> <key> <value>                 # 设置记忆（归档）
/tlmmemory set-core <名字> <key> <value>           # 设置核心记忆
/tlmmemory get <名字> <key>                          # 查看记忆
/tlmmemory list <名字>                               # 列出记忆
/tlmmemory forget <名字> <key>                       # 删除记忆
/tlmmemory export <名字> [json|text|context]         # 导出记忆
/tlmmemory summarize <名字>                          # 触发 AI 回顾补记
```

### 配置项参考
- `ChatModeEnabled` - 女仆对话模式开关
- `GlobalChatVisible` - 全局聊天可见性（控制玩家消息和女仆回复）
- `RequirePrefix` - 严格前缀模式
- `AutoChatRange` - 自动对话范围
- `PrefixPattern` - 前缀字符
- `[multi_task] Enabled` - 多工作模式开关
- `[multi_task] PollInterval` - 任务轮询间隔（tick）
- `[memory] Enabled` - 记忆系统开关
- `[memory] MaxMemories` - 每只女仆最大记忆数
- `[memory] CoreMemoryLimit` - 核心记忆全文注入上限
- `[memory] ContextPreviewLength` - 归档记忆截断长度
