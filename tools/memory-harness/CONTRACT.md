# CONTRACT.md

> 简易记忆系统 · 游戏外测试环境（Harness）协议克隆基准  
> 对照版本：Touhou Little Maid **1.5.2-forge**（`build.gradle`：`maven.modrinth:touhou-little-maid:1.5.2-forge+mc1.20.1`）  
> 附属：`tlm_sincerely`  
> 生成方式：wiki AI 文档 + 附属源码 + 本机 Gradle 缓存 jar 的 `javap` 反汇编（非二手计划摘录）

---

## 1. 对照基准

### 1.1 TLM 版本

| 项 | 值 | 来源 |
|----|-----|------|
| 依赖坐标 | `touhou-little-maid:1.5.2-forge+mc1.20.1` | `build.gradle` |
| AI 架构基线 | 1.5.1 起新 AI；1.5.2 增 `ITool.onCallAsync` | `wiki-reference/docs/wiki/dev/ai/overview.md` |
| 本机 jar | Gradle 缓存 `touhou-little-maid-1.5.2-forge+mc1.20.1.jar` | 环境 |

### 1.2 关键主模组类名清单

| 类 | 包路径 | 职责 | 来源 |
|----|--------|------|------|
| `LLMCallback` | `...ai.manager.entity` | Tool 多轮循环、去重、护栏、`addToolResult` | jar `javap`；wiki `tool.md` |
| `MaidAIChatManager` | `...ai.manager.entity` | `chat` / `buildMessage` / `normalChat` / history | jar `javap` |
| `UserPromptContexts` | `...ai.manager.entity` | `<context>` 注入与剥离 | jar `javap`；wiki `context.md` |
| `AbstractMaidContext` | `...ai.agent.context` | Context 项 `key`/`label` 基类 | jar；wiki `context.md` |
| `IMaidContext` | `...ai.agent.context` | `key()` / `label()` / `getValue(maid)` | jar；wiki `context.md` |
| `GameContextRegister` | `...ai.agent.context` | 分类/项注册、`getContext` 行格式 | jar；wiki `context.md` |
| `ContextCategory` | `...ai.agent.context` | `id` / `summary` / `promptContext` / `contextKeys` | jar |
| `ITool` | `...ai.agent.tool` | Tool 接口、`invalidParam`、`onCall`/`onCallAsync` | jar；wiki `tool.md` |
| `ToolRegister` | `...ai.agent.tool` | Tool 注册表 | wiki `tool.md` / `overview.md` |
| `UseSkillTool` | `...ai.agent.tool.implement` | 内置 `use_skill` | jar |
| `SkillLoader` | `...ai.agent.skill` | Skill 扫描/加载/列表 XML | jar；wiki `skill.md` |
| `SkillParser` | `...ai.agent.skill` | YAML front matter + body 解析 | jar；wiki `skill.md` |
| `SkillBean` / `SkillInstance` | `...ai.agent.skill` | 元数据与运行时实例 | jar |
| `LLMMessage` | `...ai.service.llm` | `systemChat`/`userChat`/`assistantChat`/`toolChat` | jar |
| `ChatMessage` | `...ai.service.llm.openai.request` | 发往 API 的 `role`/`content`/`tool_calls`/`tool_call_id` | jar |
| `HistorySummaryManager` | `...ai.manager.entity.summary` | 可选历史摘要 system 消息 | jar（`buildMessage` 调用） |
| `HistoryMessagesCheck` | `...ai.manager.entity` | 发请求前校验 messages | jar |

### 1.3 附属关键类

| 类 | 职责 | 路径 |
|----|------|------|
| `SincerelyExtension` | 注册 Tool/Context/命令 | `src/.../SincerelyExtension.java` |
| `MaidMemoryContext` | 记忆 Context 项 | `src/.../ai/context/MaidMemoryContext.java` |
| `MaidMemoryTool` | `tlm_memory` Tool | `src/.../ai/tool/MaidMemoryTool.java` |
| `TaskPriorityTool` | `task_priority`（非记忆 harness 主体） | `src/.../ai/tool/TaskPriorityTool.java` |
| `MaidMemory` / `MaidMemoryManager` | 域模型与 JSON 持久化 | `src/.../memory/` |
| `MemoryConfig` | 配置默认值与范围 | `src/.../config/subconfig/MemoryConfig.java` |
| `MemoryCommand` | `/tlmmemory` 含 summarize | `src/.../command/MemoryCommand.java` |

---

## 2. 消息结构

### 2.1 拼装顺序（一次 chat 请求）

```
messages =
  [ SYSTEM: 女仆设定 setting ]                          // buildMessage
  + [ SYSTEM?: 历史摘要 ]                               // HistorySummaryManager.appendSummaryMessage
  + [ ...history 从旧到新 ]                             // CappedQueue deque.descendingIterator
  + [ USER: addContext(maid, 玩家原文) ]               // normalChat
```

| 步骤 | 行为 | 来源 |
|------|------|------|
| 1 | `buildMessage(setting, maid, history)` 建列表 | `MaidAIChatManager.buildMessage`（jar） |
| 2 | `LLMMessage.systemChat(maid, setting)` 作为首条 | 同上 |
| 3 | `historySummaryManager.appendSummaryMessage(list)` 可能追加 system 摘要 | 同上 |
| 4 | 将 history deque **降序迭代**写入 list（旧->新） | 同上 |
| 5 | `normalChat`：`UserPromptContexts.addContext(maid, message)` | `MaidAIChatManager.normalChat`（jar） |
| 6 | `LLMMessage.userChat(maid, withContext)` 追加到 messages | 同上 |
| 7 | `addUserHistory(message)` 写入历史的是**未包裹 context 的原文** | 同上 |
| 8 | `new LLMCallback(chatManager, messages)` -> `LLMClient.chat` | 同上 |

Harness 首期可不实现 HistorySummary（默认关），但接口位置应保留在 system 与 history 之间。

> history 容量：主模组 `CappedQueue` 容量 = `AIConfig.MAID_MAX_HISTORY_LLM_SIZE`（默认 **24**，范围 1–128），超限自动驱逐最旧元素。harness `DEFAULT_LOOP_CONFIG.historyLimit = 24` 对齐（harness 额外跳过开头孤立 tool 消息以防 OpenAI 兼容端点 400）。

### 2.2 User 消息最终形态

```
<context>
…自动注入分类的行…
</context>
玩家的实际消息内容
```

| 细节 | 值 | 来源 |
|------|-----|------|
| 起始标签 | `<context>`（`CONTEXT_START`） | `UserPromptContexts`（jar ConstantValue） |
| 结束标签 | `</context>`（`CONTEXT_END`） | 同上 |
| 结束标签后 | 紧跟 `\n`，再拼玩家原文 | `UserPromptContexts.addContext` |
| 剥离正则 | `<context>[\s\S]*?</context>` | `UserPromptContexts.CONTEXT_REG` |
| 自动注入范围 | `GameContextRegister.allPromptCategories()`（`promptContext==true` 且 keys 非空） | `UserPromptContexts.appendContext` |

### 2.3 无人设自动生成（autoGenSetting）

setting 为空时，主模组不发送 system 消息，而是先触发 `onSettingIsEmpty` -> `autoGenSetting` 让 LLM 生成一段人设，保存为 setting 后再进入正常对话。

| 项 | 值 | 来源 |
|----|-----|------|
| 触发条件 | `getMessages` 返回空列表（setting 为空） | `MaidAIChatManager.tryToChat`（jar） |
| 生成指令 | 硬编码英文模板，占位符 `${chat_language}` / `${model_name}`；有描述时追加 `Character Description Section: ${model_desc}` | `MaidAIChatManager.autoGenSetting`（jar `// String` 常量） |
| 语言回退 | `chatLanguage` 空时回退 `en_us` | jar `getChatLanguage` |
| 生成参数 | 单条 user 消息、无 tools | jar |
| harness 镜像 | `packages/agent/src/auto-gen.ts`：`buildAutoGenPrompt` 逐字镜像模板（占位符 `${}`、回退 `en_us`）；`generateSetting` 单条 user 调 LLM 取文本；`agent-loop` 在 systemPrompt 为空时省略 system 消息 | 源码 |

> 注：运行时 system 消息永远等于 setting（玩家手写 **或** autoGen 生成），不存在第二条硬编码内置 system。

---

## 3. Context 块格式

### 3.1 单行格式

| 规则 | 值 | 来源 |
|------|-----|------|
| 行模板 | `"- %s: %s".formatted(label, getValue(maid))` | `GameContextRegister.lambda$getContext$3`（jar，常量 `"- %s: %s"`） |
| 展示给模型 | `- <label>: <value>` | 与 wiki `context.md` 一致 |
| `key()` | 内部寻址，**不**直接出现在 prompt 行 | wiki `context.md`；`AbstractMaidContext` |

### 3.2 分类内多行如何拼接

| 规则 | 值 | 来源 |
|------|-----|------|
| 分类内多项 | `String.join(",", list)` 后 `append("\n")` | `UserPromptContexts.lambda$appendContext$0`（jar） |
| 多分类 | 各分类块依次 append | `appendContext` 对 `allPromptCategories` forEach |

⚠ wiki `context.md` 示例把多项画成换行列表；**字节码为逗号拼接**。Harness 以 jar 为准；见「待核实」。

### 3.3 自动注入 vs 按需

| 字段 | 含义 | 来源 |
|------|------|------|
| `ContextCategory.promptContext` / `isPromptContext()` | `true`＝自动注入；`false`＝仅 `query_game_context` | jar `ContextCategory`；wiki `context.md` |
| `registerCategory(id, summary, promptContext)` | 注册 API | wiki `context.md`；`SincerelyExtension` |
| `allPromptCategories()` | 过滤 `isPromptContext()==true` 且 `contextKeys` 非空 | jar |
| `allToolCategories()` | 过滤 `isPromptContext()==false` 且 keys 非空 | jar |

### 3.4 主模组内置分类（文档）

| 分类 ID | 注入 | 来源 |
|---------|------|------|
| `status` | 自动 | wiki `overview.md` §三 |
| `world` | 自动 | 同上 |
| `equipment` / `user` / `effects` / `position` / `nearby_entities` | 按需 | 同上 |

### 3.5 本附属注册的 Context / Tool

**仅** `ai/context` 下一类、`ai/tool` 下两类：

| 类型 | ID / 说明 | 自动注入 | 来源 |
|------|-----------|----------|------|
| **Category** | id=`tlm_sincerely_memory`；summary=`Maid persistent memories: key-value facts about the player, past events, preferences`；`promptContext=true` | 是 | `SincerelyExtension.registerAIMaidContext` |
| **Context 项 key** | `tlm_sincerely_memory_list` | （随分类） | `MaidMemoryContext` 构造 `super(...)` 第一参数 |
| **Context label** | `Maid persistent memories` | - | `MaidMemoryContext` 第二参数 |
| Tool | `tlm_memory` | - | `MaidMemoryTool.id()` |
| Tool | `task_priority` | - | `TaskPriorityTool`（非记忆契约主体） |

> **易错点**：分类 id 是 `tlm_sincerely_memory`；Context **项** key 是 `tlm_sincerely_memory_list`。计划曾警告勿混淆。自动注入出现在 prompt 里的是 **label**，不是 category id / context key。

---

## 4. 记忆 Context

| 项 | 契约值 | 来源 |
|----|--------|------|
| Category id | `tlm_sincerely_memory` | `SincerelyExtension` |
| Context item key | `tlm_sincerely_memory_list` | `MaidMemoryContext` |
| label | `Maid persistent memories` | `MaidMemoryContext` |
| `promptContext` | `true`（自动注入） | `SincerelyExtension` |
| 关闭时 value | `Memory system disabled` | `MaidMemoryContext.getValue` |
| 空记忆 value | `None` | `MaidMemoryContext.getValue`；`MaidMemory.generateContextPreview` 空 map 也返回 `None` |
| 有数据时 value | `memory.generateContextPreview(CORE_LIMIT, CONTEXT_PREVIEW_LENGTH)` | `MaidMemoryContext` + `MemoryConfig` |

### 4.1 `generateContextPreview(coreLimit, previewLength)` 算法

来源：`MaidMemory.generateContextPreview`

1. 按 `LinkedHashMap` 插入序遍历，分 core / archive 两列表。  
2. **core**：最多 `coreLimit` 条，格式 `  <key>: <full value>\n`（无引号）。超限 core **直接跳过**（不降级预览——与计划 F1 所述一致，属当前权威实现）。  
3. **archive**：全部输出，`value` 超 `previewLength` 则 `substring(0, previewLength)+"..."`，格式 `  <key>: "<preview>"\n`（有引号）。  
4. Footer：`{total} memories total`，若有 core 追加 `, {n} core`，若有 archive 追加 `, {n} archive`（计数含被跳过的超限 core）。  
5. 返回整段多行字符串，作为 **单个** context value 嵌入 `- Maid persistent memories: …`。

> **M-1 F1 修复后**：超限 core 条目不再跳过，而是降级为 archive 式预览（截断 + 引号）。本节描述的是修复**前**的权威行为；golden 将冻结修复**后**行为。harness 镜像须与修复后 Java 一致。

---

## 5. Tool 循环护栏（对齐 `LLMCallback`）

全部来自 **1.5.2 jar `LLMCallback` 反汇编**（wiki `tool.md` **未**写轮次/去重数值）。

| 规则 | 值 | 来源 |
|------|-----|------|
| 最大 tool 轮次 | `MAX_TOOL_TURN_COUNT = 16` | `LLMCallback` ConstantValue；`beginToolBatch` 与 `bipush 16` 比较 `if_icmple` |
| 超限文案 | `Tool turn count exceed max count: %d`（参数为 16）后 `onFailure` + log，返回 false 停止 | `beginToolBatch` |
| 同批签名连续重复上限 | `MAX_REPEAT_TOOL_BATCH_COUNT = 2` | ConstantValue；`repeatedToolBatchCount > 2` 时停止 |
| 重复超限文案 | `Repeated identical tool batch exceed max count: %s` | `beginToolBatch` |
| 单次 tool_call 签名 | `name + "\|" + deleteWhitespace(arguments)`；function 空则 name=`unknown`、args=`""` | `getToolCallSignature`；BootstrapMethods recipe |
| 整批签名 | 各 call 签名用 `StringJoiner("\|\|")` 连接 | `createToolBatchSignature` |
| 同批完全相同 tool_call | `dedupToolCalls`：按签名 `Set` 去重，保留首次 | `dedupToolCalls`；`onFunctionCall` 先 dedup 再 `beginToolBatch` |
| 空/全被去重 | `No valid tool calls returned by LLM` -> failure | `onFunctionCall` |
| tool result 写回 | `LLMMessage.toolChat(maid, content, toolCallId)` -> `Role.TOOL` + `tool_call_id`；并 `addToolHistory` | `addToolResult`；`LLMMessage.toolChat`；`ChatMessage` 字段 `tool_call_id` |
| 必须主线程 | `addToolResult` 非服务端线程抛 `IllegalStateException` | `addToolResult` |
| 未知 tool | 文案写入 tool result，**不**整批中断：`Unknown tool '%s'. It is not registered.\nUse only tool ids from the provided schema and retry.\n` | `onSingleCall` -> `onToolErrorCall` -> `addToolResult` |
| 参数 JSON/Codec 失败 | 类似：`Failed to parse arguments...` / `Invalid arguments...` 写入 tool result 后继续 | 同上 |
| assistant 带 tool_calls | `addAssistantHistory(content, toolCalls)` + `LLMMessage.assistantChat(..., toolCalls)` 入 messages | `onFunctionCall` |

轮次语义：每次 `beginToolBatch` 先 `toolTurnCount++`，再与 16 比较；故最多成功开启 **16** 批 tool。

重复语义：新签名 -> `repeatedToolBatchCount=1`；相同 -> `++`；`>2` 停止 -> **连续相同 batch 最多允许 2 次**。

---

## 6. `tlm_memory` Tool 语义

来源：`MaidMemoryTool`（全节）。

### 6.1 元数据

| 项 | 值 |
|----|-----|
| id | `tlm_memory` |
| summary | 多行英文说明 remember/recall/forget 用途（`TOOL_DESC`） |
| 总开关 | `!MemoryConfig.ENABLED` -> 立即 `Memory system is currently disabled.` |

### 6.2 Codec 默认值

| 字段 | Codec | 默认 |
|------|-------|------|
| `action` | 必填 `STRING` | - |
| `key` | optional | `""` |
| `value` | optional | `""` |
| `importance` | optional | `MemoryEntry.ARCHIVE`（`"archive"`） |

### 6.3 action 行为

#### `remember`

| 项 | 契约 | 来源方法 |
|----|------|----------|
| 必填 | `key` 非 empty；`value` 非 empty（**Codec 默认空串，未 trim 判空**） | `remember` |
| 缺 key | `ITool.invalidParam("key", List.of("<memory key>"), "key is required for 'remember' action")` | 同上 |
| 缺 value | `invalidParam("value", List.of("<memory value>"), ...)` | 同上 |
| 满容 | `size() >= MAX_MEMORIES && !getMemories().containsKey(result.key())` -> `Memory limit reached (%d max). Delete some memories first with 'forget'.` | 同上（**M-1 F2 修复后：containsKey 用 trim 后的 key**） |
| 成功 | `set` + `save`；返回 `Remembered '%s' = "%s" (%s)`（key/value/importance **原样**，未显示 trim 后） | 同上 |
| importance | 传入 `set`；非法值在 `MaidMemory.set` 内回退 archive | `set` |

#### `recall`

| 项 | 契约 |
|----|------|
| 缺 key | `invalidParam("key", keys 或 `["no memories stored"]`, "key is required for 'recall' action")` |
| 不存在 | `Memory key '%s' not found. Available keys: %s`（`%s` 为 `memory.keys()` 的 `List.toString()`） |
| 成功 | `[%s] %s: %s` -> importance, key, value |

#### `forget`

| 项 | 契约 |
|----|------|
| 缺 key | 同 recall 的 invalidParam |
| 不存在 | 同 recall not found 文案 |
| 成功 | `forget` + `save`；`Forgot memory '%s'` |

#### 未知 action

`ITool.invalidParam("action", ["remember","recall","forget"], "Unknown action: " + action)`

### 6.4 `ITool.invalidParam` 模板

来源：主模组 `ITool.invalidParam`（jar）+ wiki `tool.md` §五：

```
Invalid parameter: <reason>. Correct usage: <name>: choose one of [<v1>,<v2>,...]
```

（合法值 `String.join(",", collection)`。）

---

## 7. 参数 schema 构建规则

来源：`MaidMemoryTool.parameters`

| 字段 | required | 描述 / enum | 说明 |
|------|----------|-------------|------|
| `action` | 是（`addProperties` 默认 required） | enum: `remember`,`recall`,`forget` | 固定 |
| `key` | **否**（第三参 `false`） | description: `The memory key (unique identifier for the memory)`；对 `memory.keys()` **逐个** `addEnumValues(existingKey)` | 动态；每轮按当前记忆 |
| `value` | 否 | description: `The memory value. Required for 'remember' action.`；**无 enum** | - |
| `importance` | 否 | description 含 core/archive；enum: `core`,`archive` | 常量 `MemoryEntry.CORE/ARCHIVE` |

### 7.1 空记忆与 enum

- 无 key 时 for 循环不调用 `addEnumValues` -> **不会**生成空 `enum: []`（只要 `StringParameter` 无 enum 时序列化省略 enum 字段——与主模组 Parameter 序列化行为一致即可）。  
- **现状怪癖**：`key` 的 enum **只含已有 key**，但 `remember` 需要新 key -> strict schema provider 可能拒写新 key。计划 F3 已记录；**当前源码仍是「全部 action 共用同一 key enum」**。

> **M-1 F3 修复后（已定案）**：`key` 字段**移除 enum**，不再枚举现有 key。空记忆天然不产生 `enum: []`。harness 镜像须与修复后 Java 一致。

---

## 8. 校验与截断（`MaidMemory.set`）

来源：`MaidMemory.set`

| 规则 | 值 |
|----|-----|
| key | `trim`；空则 **静默 return**；长度 >64 -> `substring(0,64)` |
| value | `trim`；空则静默 return；>500 -> `substring(0,500)` |
| importance | 仅 `"core"` / `"archive"`；否则强制 `archive` |
| 更新已有 | 按 **trim 后 key** 命中则更新 value/importance/updatedAt，**保留 createdAt**；**不受** MaxMemories 拦截 |
| 新建 | `size() >= MAX_MEMORIES` 则静默 return（不抛错） |
| 存储结构 | `LinkedHashMap` 插入序 |

### 8.1 Tool 层 vs `set` 层一致性

| 点 | 行为 | 备注 |
|----|------|------|
| Tool 满容判重 | `containsKey(result.key())` **未 trim** | **M-1 F2 修复后改用 trim 后 key** |
| Tool 空 key | `isEmpty()` 未 trim，空白 key 可能通过 Tool 进入 `set` 再被 trim 掉静默失败 | 边缘情况 |
| `get`/`forget` | `MaidMemory.get/forget` **不 trim** | recall/forget 对带空格 key 可能找不到已 trim 存储的条目 |

Harness 镜像应复现 **修复后 Java 行为**（F1/F2/F3 均已落），测试可单列 trim 一致性用例。

---

## 9. JSON 持久化格式

来源：`MaidMemoryManager`

| 项 | 契约 |
|----|------|
| 游戏内路径 | `{CONFIGDIR}/tlm_sincerely/maid_memories/{uuid}.json`（`FMLPaths.CONFIGDIR`） |
| Harness 建议 | `<harness-data>/maid_memories/<uuid>.json`（可互导） |
| Gson | `new GsonBuilder().setPrettyPrinting().create()` -> **标准 2-space** pretty JSON |
| 根结构 | `{ "memories": { "<key>": { "value", "importance", "createdAt", "updatedAt" } } }` |
| 键序 | 写入按 `memory.getMemories()` 迭代（LinkedHashMap）；加载按 `memObj.entrySet()` 顺序 `put` 回 LinkedHashMap |
| load 失败 / 无文件 | 返回 **空** `MaidMemory`（IOException catch 静默） |
| load 缺字段 | value 默认 `""`；importance 默认 `archive`；时间戳默认 `0` |
| save 失败 | `catch (IOException ignored)` **静默** |
| save 成功 | `MEMORY_DIR.mkdirs()` 后覆盖写 UTF-8 |

示例：

```json
{
  "memories": {
    "fav_food": {
      "value": "Pumpkin pie",
      "importance": "core",
      "createdAt": 1715472000000,
      "updatedAt": 1715472000000
    }
  }
}
```

（结构来源：`MaidMemoryManager.save`；示例数值来自 `docs/简易记忆系统模块.md`，非运行时强约束。）

---

## 10. Skill：`memory-guidance`

### 10.1 加载方式（主模组）

| 项 | 契约 | 来源 |
|----|------|------|
| 数据包路径 | `data/touhou_little_maid/skills/<skill-name>/skill.md` | wiki `skill.md`；本附属 `src/main/resources/data/touhou_little_maid/skills/memory-guidance/skill.md` |
| 配置目录 | `config/touhou_little_maid/skills/<name>/skill.md` | wiki `skill.md`；`SkillLoader` |
| 优先级 | 数据包 **高于** 配置同名 | wiki `skill.md` |
| 解析 | `SkillParser`：`---` YAML front matter + 余下 body；SnakeYAML -> `SkillBean` | jar `SkillParser` |
| 校验跳过 | name/description blank 或 body blank -> skip + warn | `SkillParser`；wiki |
| name 规则（文档） | 仅 `a-z0-9-`，不以 `-` 首尾，≤64 | wiki `skill.md` §Front Matter（**Parser 字节码未见正则强制，以文档为准/待与 SkillBean 校验对照**） |
| description | 非空，文档称 ≤1024 | wiki |

### 10.2 `use_skill` 注入

来源：`UseSkillTool`（jar）

| 项 | 行为 |
|----|------|
| id | `use_skill` |
| 参数 | 必填 `name`；enum=当前全部 skill 名（skills 非空时） |
| 未知 skill | `invalidParam("name", allNames, "Unknown skill name '%s'")` |
| 普通 skill | `body.trim()` 作为 **tool result content** 写回（`addToolResult`） |
| knowledge 类型 | RAG 子对话（`GroundedAnswerCallback`）；**非** memory-guidance 路径 |
| memory-guidance | **无** `tlm-type: knowledge` -> 普通 skill，body 直接注入 |

### 10.3 本附属 `memory-guidance` 原文摘要

**Front matter：**

```yaml
name: memory-guidance
description: >
  Guides the maid on when and how to use the persistent memory system.
  Use this when the maid needs to remember player preferences,
  important facts, or relationship milestones.
```

**Body 要点（非全文）：**

- 使用 `tlm_memory` 管理持久记忆  
- **remember 时机**：玩家明确要求记住；偏好；应保留的事实；重大事件；称呼偏好  
- **不要 remember**：寒暄/天气/闲聊；易变游戏状态；非玩家亲口；已能 `query_game_context` 得到的信息  
- **写法**：key 英文短语义名；value 简洁含语境；core=玩家明确要求或关系定义，archive=情境细节  
- **forget**：仅玩家明确要求时；勿擅自删  
- **容量**：有限；满时优先保留 core，在被要求记新内容时可 forget 旧 archive  

来源：`src/main/resources/data/touhou_little_maid/skills/memory-guidance/skill.md`

---

## 11. Summarize

来源：`MemoryCommand.summarizeMemories` **逐字**：

```
Please review our conversation so far and use tlm_memory remember to record any important information you may have missed. Use the memory-guidance skill to decide what is worth remembering.
```

（源码为相邻字符串字面量拼接，中间各有一空格；上为拼接后全文。）

触发：`maid.getAiChatManager().chat(上述文本, ChatClientInfo, player)`；语言取 TTS language，空则 `en_us`。

---

## 12. 配置默认值（`MemoryConfig`）

| 配置键（toml） | 字段 | 默认 | 范围 | 来源 |
|----------------|------|------|------|------|
| `Enabled` | `ENABLED` | `true` | boolean | `MemoryConfig.init` |
| `MaxMemories` | `MAX_MEMORIES` | `50` | 1–200 | 同上 |
| `CoreMemoryLimit` | `CORE_LIMIT` | `10` | 0–50 | 同上；注释：`0 = all archive preview only`（M-1 F1 修复后语义一致：超限 core 降级预览） |
| `ContextPreviewLength` | `CONTEXT_PREVIEW_LENGTH` | `30` | 10–200 | 同上 |

配置段：`[memory]`（`builder.push("memory")`）。

---

## 13. 附属 Tool 清单（完整）

| Tool id | 类 | harness 记忆范围 |
|---------|-----|------------------|
| `tlm_memory` | `MaidMemoryTool` | **完整实现** |
| `task_priority` | `TaskPriorityTool` | 可 stub/禁用 |

主模组内置（wiki `overview.md`）：`use_skill`, `query_game_context`, `switch_follow_state`, `switch_work_task`, `switch_schedule`, `switch_sit`, `query_minecraft_wiki`。

---

## 待核实清单

1. **Context 多行分隔符**：jar 中 `UserPromptContexts` 对同分类多项使用 `String.join(",", …)`；wiki 示例为换行。Harness 应以 jar 为准，并用 golden 对照真实游戏一次 chat 的 raw user message 再锁定。  
2. **`getToolCallSignature` 拼接**：BootstrapMethods 为 `name|args`；args 经 `StringUtils.deleteWhitespace`。若上游 arguments 已 minify，行为依赖模型输出空白。  
3. **HistorySummary 具体 system 文案与触发条件**：仅确认 `appendSummaryMessage` 挂在 `buildMessage`；正文未反汇编 `HistorySummaryManager`（首期可关）。  
4. **`SkillBean` name 长度/字符集**：wiki 有 64/`a-z0-9-` 规则；`SkillParser` 仅查 blank，未见正则——是否在 `SkillBean` setter 或其它层强制 **未从本 jar 片段完全证实**。  
5. **Gson pretty 缩进**：`setPrettyPrinting()` 默认 2-space（Gson 惯例）；未对写出文件做字节级采样。Golden 建议语义比对或固定 Gson 版本。  
6. **`StringParameter` 无 enum 时 JSON schema 是否省略 `enum` 键**：空记忆时 Java 不调用 `addEnumValues`；最终 schema 形状依赖主模组 Parameter 序列化（未反汇编该路径）。契约要求：**不得出现 `enum: []`**。  
7. **主模组 system 设定如何生成**（PAPI/角色卡）：`buildMessage` 只接收 setting 字符串；展开逻辑不在本契约记忆范围内。  
8. **`addUserHistory` 是否剥离 context**：`normalChat` 传入的是原始 `message`（未 addContext）——已核实；历史中不应残留 `<context>`（除非玩家原文自带）。
