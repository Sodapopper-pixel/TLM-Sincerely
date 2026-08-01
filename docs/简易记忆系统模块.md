# 简易记忆系统 — 开发文档

## 功能概述

为女仆提供持久化的键值对记忆存储，让女仆能"记住"玩家的偏好、历史事件和个人信息。记忆自动注入 AI 上下文，AI 可通过 Tool 自主读写，玩家可通过命令管理。

**核心能力**：
- 每只女仆独立记忆存储（JSON 持久化，按 UUID 索引）
- 核心记忆（core）全文注入 AI 上下文，归档记忆（archive）仅注入索引预览
- AI 可自主调用 `tlm_memory` Tool 写入/读取/遗忘记忆
- `memory-guidance` Skill 引导 AI 何时应记录记忆
- 玩家通过 `/tlmmemory` 命令管理记忆和导出
- 配置项可调节最大记忆数、核心记忆上限、预览截断长度

---

## 架构与数据流

```
┌─────────────────────────────────────────────────────────────┐
│                      玩家 / AI                               │
├─────────────────────────────────────────────────────────────┤
│  命令层: /tlmmemory set/get/list/forget/export/summarize     │
│          /tlmmemory set-core (核心记忆)                       │
├─────────────────────────────────────────────────────────────┤
│  AI Tool 层: tlm_memory                                     │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  remember(key, value, importance) → 写入记忆          │   │
│  │  recall(key)                      → 读取全文          │   │
│  │  forget(key)                      → 删除记忆          │   │
│  └──────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────┤
│  AI Context 层 (自动注入, promptContext=true)                │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  核心记忆 (≤ 10 条): key: "full value"                │   │
│  │  归档记忆:          key: "truncated preview..."       │   │
│  └──────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────┤
│  Skill 层: memory-guidance (数据包)                          │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  引导 AI: 何时记录/何时不记录/如何命名/容量管理       │   │
│  └──────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────┤
│  持久化层: MaidMemoryManager (Gson JSON)                     │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  config/tlm_sincerely/maid_memories/<uuid>.json       │   │
│  └──────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────┤
│  配置层: MemoryConfig (ForgeConfigSpec)                      │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Enabled / MaxMemories / CoreLimit / PreviewLength    │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 数据流时序

```
AI 对话
  → 主模组注入游戏 context (血量、天气、位置等)
  → MaidMemoryContext.getValue() 注入记忆索引预览
  → AI 收到完整上下文

AI 决定记录偏好:
  → AI 调用 tlm_memory remember("player_hobby", "likes rain")
  → MaidMemoryTool.onCall() → MaidMemory.set() → MaidMemoryManager.save()
  → 写入 <uuid>.json

AI 需要回忆细节:
  → AI 从 context 预览中看到 key="player_hobby"
  → AI 调用 tlm_memory recall("player_hobby")
  → 返回完整值 "likes rain, especially thunderstorms"

玩家用命令管理:
  → /tlmmemory set 琪琪 fav_color red
  → MemoryCommand → MaidFinder.resolveMaid → MaidMemoryManager.load/save
  → 即时更新 JSON 文件
```

---

## 文件清单

### 核心逻辑层 (`memory/`)

| 文件 | 职责 |
|------|------|
| `MaidMemory.java` | 数据模型：MemoryEntry record（value/importance/createdAt/updatedAt）+ LinkedHashMap 封装 + generateContextPreview |
| `MaidMemoryManager.java` | 全局管理器：Gson JSON 读写，per-UUID 文件管理 |

### AI 层 (`ai/`)

| 文件 | 职责 |
|------|------|
| `ai/context/MaidMemoryContext.java` | AI Context 项：注册为 `tlm_sincerely_memory` 分类，自动注入记忆索引 |
| `ai/tool/MaidMemoryTool.java` | AI Tool：remember/recall/forget 三个 action，自动枚举已有 key |

### 命令层 (`command/`)

| 文件 | 职责 |
|------|------|
| `MemoryCommand.java` | Brigadier 命令注册：set/get/list/forget/export/summarize + set-core |
| `UnicodeWordArgument.java` | 自定义参数类型：读取直到空格的词，支持 Unicode（解决 MC 1.20.1 限制） |

### Skill 数据包 (`data/`)

| 文件 | 职责 |
|------|------|
| `data/touhou_little_maid/skills/memory-guidance/skill.md` | Skill 文件：AI 记忆行为指导（何时记/如何命名/容量管理） |

### 配置层 (`config/subconfig/`)

| 文件 | 职责 |
|------|------|
| `MemoryConfig.java` | ForgeConfigSpec 配置：Enabled, MaxMemories(50), CoreLimit(10), PreviewLength(30) |

---

## 核心 API 参考

### MaidMemory

```java
// 数据记录
record MemoryEntry(String value, String importance, long createdAt, long updatedAt)
// 常量
MemoryEntry.CORE = "core"
MemoryEntry.ARCHIVE = "archive"

// MaidMemory 实例
MaidMemory memory = new MaidMemory();

// 写入/更新记忆（自动校验 key ≤ 64 字符、value ≤ 500 字符、importance 校验）
memory.set("key", "value", MemoryEntry.ARCHIVE);

// 读取
Optional<MemoryEntry> entry = memory.get("key");

// 删除
memory.forget("key");

// 遍历
memory.keys();       // List<String>
memory.size();       // int
memory.isEmpty();    // boolean
memory.getMemories(); // Map<String, MemoryEntry>

// 生成 AI 上下文预览（core 全文 + archive 截断）
String preview = memory.generateContextPreview(coreLimit, previewLength);
```

### MaidMemoryManager

```java
// 加载/保存（自动管理目录创建）
MaidMemory memory = MaidMemoryManager.load(maidUuid);
MaidMemoryManager.save(maidUuid, memory);

// UUID 文件数量
int total = MaidMemoryManager.count();
```

### MaidMemoryTool (AI Tool)

Tool ID: `tlm_memory`

#### action=remember

| 参数 | 类型 | 说明 |
|------|------|------|
| `key` | String | 记忆键，最多 64 字符，必填 |
| `value` | String | 记忆值，最多 500 字符，必填 |
| `importance` | String | `"core"` 或 `"archive"`，默认 archive |

#### action=recall

| 参数 | 类型 | 说明 |
|------|------|------|
| `key` | String | 记忆键，必填。枚举值为当前女仆全部已有 key |

#### action=forget

| 参数 | 类型 | 说明 |
|------|------|------|
| `key` | String | 记忆键，必填。枚举值为当前女仆全部已有 key |

---

## 命令详解

| 命令 | 效果 |
|------|------|
| `/tlmmemory set <名字> <key> <value>` | 设置归档记忆 |
| `/tlmmemory set-core <名字> <key> <value>` | 设置核心记忆 |
| `/tlmmemory get <名字> <key>` | 查看记忆详情（值、重要性、创建/更新时间） |
| `/tlmmemory list <名字>` | 列出全部记忆（★核心 ·归档，含 30 字符预览） |
| `/tlmmemory forget <名字> <key>` | 删除指定记忆 |
| `/tlmmemory export <名字> [json\|text\|context]` | 导出记忆（JSON 原文 / Markdown / AI 视角 context） |
| `/tlmmemory summarize <名字>` | 触发 AI 回顾本轮对话，补写遗漏记忆 |

**名字选择器支持两种格式**：
- `琪琪` → 模糊名称匹配（MaidFinder.findByName，取最近）
- `uuid:550e8400-e29b-41d4-a716-446655440000` → UUID 精确匹配

---

## UIConfig (Cloth Config GUI)

```
## 记忆系统 ##
 启用记忆系统         [开/关]  默认开
 最大记忆数           [1-200]  默认 50
 核心记忆上限         [0-50]   默认 10（0 = 全部仅预览）
 归档预览长度         [10-200] 默认 30
```

配置路径：`config/tlm_sincerely-common.toml` → `[memory]`

---

## 配置文件格式

### config/tlm_sincerely-common.toml

```toml
[memory]
    # Enable the maid memory system
    Enabled = true
    # Maximum number of memories per maid
    MaxMemories = 50
    # Maximum number of core memories injected in full text (0 = all archive preview only)
    CoreMemoryLimit = 10
    # Truncation length for archive memory values in context preview
    ContextPreviewLength = 30
```

### config/tlm_sincerely/maid_memories/<uuid>.json

```json
{
    "memories": {
        "fav_food": {
            "value": "Pumpkin pie",
            "importance": "core",
            "createdAt": 1715472000000,
            "updatedAt": 1715472000000
        },
        "weather_story": {
            "value": "On a rainy Tuesday, the player told me about their pet rabbit",
            "importance": "archive",
            "createdAt": 1715299200000,
            "updatedAt": 1715299200000
        }
    }
}
```

---

## 注意事项与已知问题

### Brigadier Unicode 兼容性

Minecraft 1.20.1 使用的 Brigadier 1.1.8 中，`StringReader.readUnquotedString()` 的 `isAllowedInUnquotedString(char)` 仅允许 ASCII 字符（`0-9A-Za-z_-.+`）。中文等 Unicode 字符会立即停止读取，导致参数值为空串并引发 `"参数后应有空格分隔"` 错误。

**解决方案**：自建 `UnicodeWordArgument` 参数类型，逐字符读取直到空格，支持任意编码。需通过 `ArgumentTypeInfos.registerByClass()` + `SingletonArgumentInfo` 注册序列化器，否则玩家登录时命令树序列化失败导致 `"无效的玩家数据"`。

### Brigadier 命令树限制

`greedyString` 会吞掉全部剩余输入，故不能在其后挂 `literal` 子节点（如 `core`/`archive`）。解决方案：拆分为独立的 `set`（归档）和 `set-core`（核心）子命令。

### 构造函数多重调用

由于 `@Mod` 和 `@LittleMaidExtension` 共存导致双重实例化，`registerArgumentTypes()` 使用静态 `boolean argumentTypesRegistered` 防重入。

### 记忆容量与 Token 预算

- 50 条记忆的索引预览约 300-500 token，处于可接受范围
- 核心记忆（≤ 10 条）全文注入，token 消耗视 value 长度而定
- 关闭 `Enabled` 时 Context 返回 "Memory system disabled"，Tool 返回禁用提示

### 数据持久化

- JSON 文件路径由 `FMLPaths.CONFIGDIR` 确定
- `savePresets()` 在每次修改后立即调用（实时保存模式）
- 记忆不跟随女仆 NBT 数据，女仆死亡/消失后记忆仍保留（按 UUID 索引）
- 多玩家环境中，服务端共享 JSON 文件

### 已知问题

| 问题 | 描述 |
|------|------|
| 无 GUI 编辑界面 | 计划后续仿照 TaskPriorityScreen 实现 |
| 无语义搜索 | 记忆完全对齐 key 查找，AI 从 context 预览中识别 target key |
| 无女仆间共享 | 属于后续"女仆间交流系统"范畴 |

---

## 扩展点

### 未来可改进方向
1. **GUI 管理界面**：仿照 TaskPriorityScreen 双列布局（左侧空白记忆条目 + 右侧已有记忆）
2. **日记本物品**：游戏内物品，右键打开 GUI 浏览/编辑对应女仆的记忆
3. **导出/导入**：跨存档迁移记忆
4. **自动摘要**：AI 自动将长对话摘要为结构化记忆
5. **记忆预设/模板**：为性格系统提供初始记忆模板
