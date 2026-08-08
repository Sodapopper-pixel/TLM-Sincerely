# CONTRACT.md

> 简易记忆系统 · 游戏外测试环境（Harness）协议克隆基准  
> 对照版本：Touhou Little Maid **1.5.3-forge**（`build.gradle`：`maven.modrinth:touhou-little-maid:1.5.3-forge+mc1.20.1`）  
> 附属：`tlm_sincerely`  
> 生成方式：wiki AI 文档 + 附属源码 + 本机 Gradle 缓存 jar 的 `javap` 反汇编（非二手计划摘录）

---

## 1. 对照基准

### 1.1 TLM 版本

| 项 | 值 | 来源 |
|----|-----|------|
| 依赖坐标 | `touhou-little-maid:1.5.3-forge+mc1.20.1` | `build.gradle` |
| AI 架构基线 | 1.5.1 起新 AI；1.5.2 增 `ITool.onCallAsync`；1.5.3 增异步回调主线程兜底 `LLMCallback.runOnServerThread` | wiki `overview.md`；1.5.3 jar |
| 本机 jar | Gradle 缓存 `touhou-little-maid-1.5.3-forge+mc1.20.1.jar` | 环境 |

### 1.2 关键主模组类名清单

| 类 | 包路径 | 职责 | 来源 |
|----|--------|------|------|
| `LLMCallback` | `...ai.manager.entity` | Tool 多轮循环、去重、护栏、`addToolResult` | jar `javap`；wiki `tool.md` |
| `MaidAIChatManager` | `...ai.manager.entity` | `chat` / `buildMessage` / `normalChat` / history | jar `javap` |
| `UserPromptContexts` | `...ai.manager.entity` | `<context>` 注入与剥离 | jar `javap`；wiki `context.md` |
| `GameContextRegister` | `...ai.agent.context` | 分类/项注册、`getContext` 行格式 | jar；wiki `context.md` |
| `ITool` | `...ai.agent.tool` | Tool 接口、`invalidParam`、`onCall`/`onCallAsync` | jar；wiki `tool.md` |
| `UseSkillTool` | `...ai.agent.tool.implement` | 内置 `use_skill`（主模组内置，本附属 M-2.4 后无 skill 可加载） | jar |
| `LLMMessage` | `...ai.service.llm` | `systemChat`/`userChat`/`assistantChat`/`toolChat` | jar |
| `HistorySummaryManager` | `...ai.manager.entity.summary` | 可选历史摘要 system 消息 | jar |
| `ArrayParameter` | `...ai.service.function.schema.parameter` | 数组参数（M-2.5 `merge.keys` 用） | 1.5.3 jar `jar tf` |

### 1.3 附属关键类

| 类 | 职责 | 路径 |
|----|------|------|
| `SincerelyExtension` | 注册 Tool/Context/命令 | `src/.../SincerelyExtension.java` |
| `MaidMemoryContext` | 记忆 Context 项（4 参 preview） | `src/.../ai/context/MaidMemoryContext.java` |
| `MaidMemoryTool` | `tlm_memory` Tool（5 action） | `src/.../ai/tool/MaidMemoryTool.java` |
| `MaidMemory` / `MaidMemoryManager` | 域模型（7 字段 + meta）与 JSON 持久化 | `src/.../memory/` |
| `MemoryMaintenanceManager` | 后台自动整理（M-2.6） | `src/.../memory/MemoryMaintenanceManager.java` |
| `MemoryConfig` | 配置默认值与范围（11 项） | `src/.../config/subconfig/MemoryConfig.java` |
| `MemoryCommand` | `/tlmmemory` 含 summarize | `src/.../command/MemoryCommand.java` |
| `MemoryGuidanceMixin` | system 引导注入（M-2.4） | `src/.../mixin/MemoryGuidanceMixin.java` |
| `ChatBubbleSilenceMixin` / `TtsSilenceMixin` / `MaidChatBroadcastMixin` | 维护轮静默（M-2.6） | `src/.../mixin/` |

---

## 2. 消息结构

### 2.1 拼装顺序（一次 chat 请求）

```
messages =
  [ SYSTEM: 女仆设定 setting ]                          // buildMessage: systemChat(setting)
  + [ SYSTEM?: 记忆引导 guidance ]                      // MemoryGuidanceMixin @Redirect (M-2.4)，在 appendSummaryMessage 之前注入
  + [ SYSTEM?: 历史摘要 ]                               // HistorySummaryManager.appendSummaryMessage
  + [ ...history 从旧到新 ]                             // CappedQueue deque.descendingIterator
  + [ USER: addContext(maid, 玩家原文) ]               // normalChat
```

| 步骤 | 行为 | 来源 |
|------|------|------|
| 1 | `buildMessage(setting, maid, history)` 建列表 | `MaidAIChatManager.buildMessage`（jar） |
| 2 | `LLMMessage.systemChat(maid, setting)` 作为首条 | 同上 |
| 3 | **M-2.4**：`MemoryGuidanceMixin` `@Redirect` 拦截 `appendSummaryMessage`，在调用前向 list 注入 `systemChat(maid, GUIDANCE_TEXT)`（`MemoryGuidance` 开 + `Enabled` 开时） | `MemoryGuidanceMixin`（源码） |
| 4 | `historySummaryManager.appendSummaryMessage(list)` 可能追加 system 摘要 | 同上 |
| 5 | 将 history deque **降序迭代**写入 list（旧->新） | 同上 |
| 6 | `normalChat`：`UserPromptContexts.addContext(maid, message)` | `MaidAIChatManager.normalChat`（jar） |
| 7 | `addUserHistory(message)` 写入历史的是**未包裹 context 的原文** | 同上 |

harness 镜像（`agent-loop.ts`）：`prefix = [setting?(非空), guidance?(enabled&&memoryGuidance)]`，messages = `[...prefix, ...history, user]`。`userIdx = prefix.length + history.length`。

> **history 容量与压缩（1.5.3 jar 核实）**：`MaidAIChatData` 构造器 `history = new CappedQueue<>(512)`--**512 硬编码字面量，非配置**（`AIConfig` 无 `MAID_MAX_HISTORY_LLM_SIZE`）。摘要压缩是独立 token 触发机制：上一轮 token ≥ `MAID_HISTORY_COMPRESS_TOKEN_LIMIT`（默认 48K）时下一次 chat 前异步摘要最旧 `size-32` 条（可压缩数 <4 跳过），截断 1600 字符存 `compressedSummary` 作第二条 system，`pollLast` 删除快照条数。harness `historyLimit = 512` 仅镜像硬上限 + 最旧驱逐；**不复制**压缩机制。

### 2.2 User 消息最终形态

```
<context>
…自动注入分类的行…
</context>
玩家的实际消息内容
```

起始 `<context>`、结束 `</context>` 后紧跟 `\n` 再拼原文；剥离正则 `<context>[\s\S]*?</context>`（均 `UserPromptContexts` jar ConstantValue）。

### 2.3 无人设自动生成（autoGenSetting）

setting 为空时触发 `onSettingIsEmpty` -> `autoGenSetting`，硬编码英文模板占位符 `${chat_language}`/`${model_name}`（可选 `${model_desc}`），语言回退 `en_us`，单条 user 无 tools。harness `auto-gen.ts` 逐字镜像。

---

## 3. Context 块格式

### 3.1 单行格式

行模板 `"- %s: %s".formatted(label, getValue(maid))` -> `- <label>: <value>`（`GameContextRegister.lambda$getContext$3` jar 常量）。

### 3.2 分类内多行拼接

✅ 已核实（1.5.3 jar 常量池 `#127 = String ","`）：同分类多 key 各 `- <label>: <value>` 经 `String.join(",", list)`（**逗号无空格**）拼成**一行**，行末 `\n`。多分类各占一行。harness `context-builder.ts` 将 status/world 折叠为单合并 value（stub 简化），分隔符对齐 `,`。

### 3.3 自动注入 vs 按需

`ContextCategory.promptContext`：`true`=自动注入，`false`=仅 `query_game_context`。`allPromptCategories()` 过滤 `promptContext==true && keys 非空`。

### 3.4 本附属注册的 Context

| 类型 | ID / 说明 | 自动注入 |
|------|-----------|----------|
| Category | id=`tlm_sincerely_memory`；summary=`Maid persistent memories: key-value facts about the player, past events, preferences`；`promptContext=true` | 是 |
| Context key | `tlm_sincerely_memory_list`；label=`Maid persistent memories` | - |

---

## 4. 记忆 Context

| 项 | 契约值 | 来源 |
|----|--------|------|
| 关闭时 value | `Memory system disabled` | `MaidMemoryContext.getValue` |
| 空记忆 value | `None` | 同上 |
| 有数据时 value | `memory.generateContextPreview(CORE_LIMIT, CONTEXT_PREVIEW_LENGTH, PREVIEW_MODE, SHOW_SOURCE)` **4 参**（M-2.8/M-2.7） | `MaidMemoryContext`（源码） |

### 4.1 `generateContextPreview(coreLimit, previewLength, previewMode, showSource)` 算法

来源：`MaidMemory.generateContextPreview`（M-2 后 4 参版；2 参版 = `("full", false)`）

1. 空 map -> `"None"`。
2. 按 LinkedHashMap 插入序分 core / archive 两列表。`keysOnly = "keys_only".equals(previewMode)`。
3. **core**：前 `coreLimit` 条全量 `  <key>: <value>`（+ ` (<source>)` 当 `showSource && source 非空`）；超限 core 降级为 archive 式 `  <key>: "<preview>"`（+ source）（M-1 F1）。
4. **archive**：`keysOnly` -> `  <key>\n`（无 source）；否则 `  <key>: "<preview>"`（+ source）。preview = value 超 previewLength 则 `substring(0,previewLength)+"..."`。
5. Footer：`{total} memories total`，core>0 追加 `, {n} core`，archive>0 追加 `, {n} archive`（计数含降级 core）。Footer 无尾随 `\n`。

---

## 5. Tool 循环护栏（对齐 `LLMCallback`，1.5.3 jar）

| 规则 | 值 |
|------|-----|
| 最大 tool 轮次 | `16`（`beginToolBatch` 先 `++` 再比较） |
| 超限文案 | `Tool turn count exceed max count: 16` |
| 同批签名连续重复上限 | `2`（`>2` 停止） |
| 重复超限文案 | `Repeated identical tool batch exceed max count: <sig>` |
| 单次签名 | `name + "\|" + deleteWhitespace(args)` |
| 同批去重 | 按签名 `Set` 去重保留首次；空/全去重 -> `No valid tool calls returned by LLM` |

---

## 6. `tlm_memory` Tool 语义

来源：`MaidMemoryTool`（M-2 后 5 action）。

### 6.1 元数据

| 项 | 值 |
|----|-----|
| id | `tlm_memory` |
| summary | 多行英文说明 remember/recall/forget/search/merge（`TOOL_DESC`） |
| 总开关 | `!ENABLED` -> `Memory system is currently disabled.` |

### 6.2 Codec 默认值

| 字段 | Codec | 默认 |
|------|-------|------|
| `action` | 必填 `STRING` | - |
| `key` | optional | `""` |
| `value` | optional | `""` |
| `importance` | optional | `"archive"` |
| `query` | optional（M-2.1） | `""` |
| `keys` | `Codec.list(STRING)` optional（M-2.5） | `List.of()` |

### 6.3 维护模式收窄（M-2.6）

`MemoryMaintenanceManager.isMaintaining(maidUuid)` 为 true 时：先 `recordToolActivity`；若 `action` 不在 `{search, recall, merge}` -> 返回 `Only search/recall/merge are allowed during memory maintenance.`。harness 镜像：`ctx.maintaining` 标志（默认 false，可手动设置测试收窄）。

### 6.4 action 行为

#### `remember`

| 项 | 契约 | 来源 |
|----|------|------|
| 必填 | `key` 非 empty；`value` 非 empty（isEmpty 未 trim） | `remember` |
| 满容（M-2.2） | `size() >= MAX && !containsKey(trim(key))`：`AutoEvict=false` -> `Memory limit reached (N max). Delete some memories first with 'forget'.`；`AutoEvict=true` 且有 archive -> forget 最旧 archive + set + save + `Remembered 'k' = "v" (imp). Evicted oldest archive '<old>' to make room.`；全 core -> `Memory limit reached (N max) and all entries are core. Ask the player which memory to forget.` | `remember` + `findOldestArchiveKey` |
| 成功 | `set(key, value, importance, source)` + save；`Remembered 'k' = "v" (imp)`（key/value/importance 原样未 trim 显示） | 同上 |
| source | `getSource(maid)` = owner 名（harness 用 `maid.userName`） | `getSource` |
| 整理触发 | `TIDY_ENABLED && msg.startsWith("Remembered")` -> `checkAndScheduleTidy` | `onCall` |

#### `recall`

| 项 | 契约 |
|----|------|
| 缺 key | `invalidParam("key", keys 或 ["no memories stored"], ...)` |
| 不存在 | `Memory key '%s' not found. Available keys: %s`（`%s` = `memory.keys()` 的 `List.toString()` = `[a, b]`） |
| 成功 | `touch(key)` + save（M-2.3：`accessCount++`, `lastAccessedAt=now`）；输出 `[imp] key: value`，`SHOW_SOURCE && source 非空` 追加 ` (source: <src>)` |

#### `forget`

| 项 | 契约 |
|----|------|
| 缺 key | 同 recall invalidParam |
| 不存在 | 同 recall not found（`containsKey(key)` 未 trim） |
| 成功 | `forget` + save；`Forgot memory 'k'` |

#### `search`（M-2.1）

| 项 | 契约 |
|----|------|
| 必填 | `query` trim 后非空 |
| 匹配 | `lower(query)` 是 `lower(key)` 或 `lower(value)` 子串即命中 |
| 上限 | 最多 10 条，插入序 |
| 返回 | 每行 `  <key>: "<preview>"\n`（preview 复用 `CONTEXT_PREVIEW_LENGTH`），footer `<n> matches`（无尾随 `\n`） |
| 无命中 | `No memories match '<q>'.` |
| 统计 | **不更新**访问统计 |

#### `merge`（M-2.5）

| 项 | 契约 |
|----|------|
| 参数 | `keys`（2-5，`ArrayParameter` setRange(2,5) uniqueItems）；`key` 目标；`value` 合并值 |
| 校验 | keys 数 2-5（否则 `merge requires 2-5 source keys`）；key/value 非空；每个源 key trim 后存在且 `importance==archive`（否则 `merge can only combine existing archive memories; core entries are protected.`）；源互不相同（`Duplicate source key`）；目标 key trim 后若为现有 core -> `Target key '<k>' is a core entry and cannot be overwritten by merge.` |
| 原子 | 逐个 forget 源 -> `set(targetKey, value, ARCHIVE, source)` -> save |
| 返回 | `Merged <n> entries into '<k>'` |

#### 未知 action

`invalidParam("action", ["remember","recall","forget","search","merge"], "Unknown action: " + action)`

### 6.5 `ITool.invalidParam` 模板

```
Invalid parameter: <reason>. Correct usage: <name>: choose one of [<v1>,<v2>,...]
```

（合法值 `String.join(",", collection)`。）

---

## 7. 参数 schema 构建规则

来源：`MaidMemoryTool.parameters`（M-2 后）

| 字段 | required | 描述 / enum |
|------|----------|-------------|
| `action` | 是 | enum: `remember`,`recall`,`forget`,`search`,`merge` |
| `key` | 否（`false`） | description 含 remember/recall/forget/merge 用途；**无 enum**（M-1 F3 移除） |
| `value` | 否 | `Required for 'remember' and 'merge' actions.` |
| `importance` | 否 | enum: `core`,`archive` |
| `query` | 否（M-2.1） | `Search query for 'search' action. Returns matching memory keys and previews (up to 10).` |
| `keys` | 否（M-2.5） | `ArrayParameter` setItems(String) setRange(2,5) setUniqueItems；`Source keys to merge (2-5 existing archive entries)` |

---

## 8. 校验与截断（`MaidMemory.set`）

来源：`MaidMemory.set(key, value, importance, source)`（M-2.3/M-2.7 后含 source）

| 规则 | 值 |
|----|-----|
| key | `trim`；空静默 return；>64 -> `substring(0,64)` |
| value | `trim`；空静默 return；>500 -> `substring(0,500)` |
| importance | 非 core/archive -> 强制 archive |
| 更新已有 | 按 trim 后 key 命中：更新 value/importance/updatedAt，**保留** createdAt/lastAccessedAt/accessCount；source = 新 source 非空则用新，否则保留原 |
| 新建 | `size() >= MAX_MEMORIES` 静默 return；否则建（lastAccessedAt=0, accessCount=0, source=传入或""） |
| `touch(key)` | M-2.3：lastAccessedAt=now, accessCount++（recall 调用） |
| `findOldestArchiveKey()` | M-2.2：插入序首个 archive |
| `search(query)` | M-2.1：lower 子串匹配 key/value，≤10 |
| 存储结构 | `LinkedHashMap` 插入序 |

---

## 9. JSON 持久化格式

来源：`MaidMemoryManager`（M-2 后 7 字段 + meta）

| 项 | 契约 |
|----|------|
| 游戏内路径 | `{CONFIGDIR}/tlm_sincerely/maid_memories/{uuid}.json` |
| Harness | `<harness-data>/maid_memories/<uuid>.json`（可互导） |
| Gson | `setPrettyPrinting()` -> 2-space pretty JSON |
| 根结构 | `{ "memories": { "<key>": { 7 字段 } }, "meta": { "lastTidyAt": <long> } }` |
| MemoryEntry 7 字段 | `value`, `importance`, `createdAt`, `updatedAt`, `lastAccessedAt`, `accessCount`, `source`（M-2.3/M-2.7） |
| meta | `{ "lastTidyAt": <long> }`（M-2.6） |
| load 缺字段 | value 默认 `""`；importance 默认 `archive`；时间戳默认 `0`；source 默认 `""`；meta 缺省 `lastTidyAt=0`（向后兼容旧 4 字段无 meta 存档） |
| save 失败 | `catch (IOException ignored)` 静默 |

示例：

```json
{
  "memories": {
    "fav_food": {
      "value": "Pumpkin pie",
      "importance": "core",
      "createdAt": 1715472000000,
      "updatedAt": 1715472000000,
      "lastAccessedAt": 0,
      "accessCount": 0,
      "source": "terk"
    }
  },
  "meta": {
    "lastTidyAt": 0
  }
}
```

---

## 10. Memory Guidance 注入（M-2.4，替代 Skill）

M-2.4 移除了 `memory-guidance` skill（消除触发悖论），改为 `MemoryGuidanceMixin` 注入 system 消息。

| 项 | 契约 | 来源 |
|----|------|------|
| 机制 | `@Redirect` 拦截 `MaidAIChatManager.buildMessage` 中的 `HistorySummaryManager.appendSummaryMessage(List)`，调用原方法前向 list 添加 `LLMMessage.systemChat(maid, GUIDANCE_TEXT)` | `MemoryGuidanceMixin`（源码） |
| 触发条件 | `ENABLED && MEMORY_GUIDANCE`（默认均 true） | 同上 |
| 消息次序 | `[setting][guidance][summary?][history][user+context]` | 同上 |
| GUIDANCE_TEXT | 约 150 token 常量，逐字镜像于 harness `agent-loop.ts` `GUIDANCE_TEXT` | `MemoryGuidanceMixin.GUIDANCE_TEXT` |
| 配置 | `MemoryGuidance`（bool，默认 true）；关闭不注入 | `MemoryConfig` |
| Mixin 注册 | `tlm_sincerely.mixins.json` `mixins` 数组登记 4 个服务端 mixin（`@Mixin(remap=false)`） | `tlm_sincerely.mixins.json` |

**GUIDANCE_TEXT 全文**：

```
## Memory Guidelines
You have persistent memory, listed under "Maid persistent memories" in context. Manage it with the tlm_memory tool.
Call remember when: the player explicitly asks you to remember; the player states a lasting preference, personal fact, or preferred form of address; a significant event occurs (gift, promise, milestone).
Do NOT remember: small talk, weather, transient game state, anything not said to you directly, anything already available via query_game_context.
Key: short semantic English snake_case (e.g. player_hobby). Value: one concise sentence with context (who/what/when). Importance: core = explicitly requested or relationship-defining; archive = contextual details.
Forget only when the player explicitly asks. Capacity is limited; when full, the oldest archive entry is evicted automatically.
Use search to find old memories, recall to read full detail.
```

> `use_skill` 仍是主模组内置 Tool（harness 保留注册），但 M-2.4 后本附属无 skill 文件，skills 列表为空。

---

## 11. Summarize

来源：`MemoryCommand.SUMMARIZE_PROMPT`（M-2.4/D6 自包含，不引用 skill）**逐字**：

```
Please review our conversation so far and use tlm_memory remember to record anything you missed: things the player asked you to remember, lasting preferences, personal facts, significant events. Use short semantic English keys and one concise sentence with context. Do not record small talk or transient game state.
```

触发：`maid.getAiChatManager().chat(SUMMARIZE_PROMPT, ChatClientInfo, player)`；语言取 TTS language，空则 `en_us`。harness `server.ts` `SUMMARIZE_PROMPT` 逐字镜像。

---

## 12. 配置默认值（`MemoryConfig`，11 项）

| 配置键（toml） | 字段 | 默认 | 范围 | 引入 |
|----------------|------|------|------|------|
| `Enabled` | `ENABLED` | `true` | bool | M-1 |
| `MaxMemories` | `MAX_MEMORIES` | `50` | 1–200 | M-1 |
| `CoreMemoryLimit` | `CORE_LIMIT` | `10` | 0–50 | M-1 |
| `ContextPreviewLength` | `CONTEXT_PREVIEW_LENGTH` | `30` | 10–200 | M-1 |
| `AutoEvict` | `AUTO_EVICT` | `true` | bool | M-2.2 |
| `MemoryGuidance` | `MEMORY_GUIDANCE` | `true` | bool | M-2.4 |
| `TidyEnabled` | `TIDY_ENABLED` | `true` | bool | M-2.6 |
| `TidyThreshold` | `TIDY_THRESHOLD` | `0.8` | 0.5–1.0 | M-2.6 |
| `TidyCooldownMinutes` | `TIDY_COOLDOWN_MINUTES` | `20` | 1–1440 | M-2.6 |
| `ShowSource` | `SHOW_SOURCE` | `false` | bool | M-2.7 |
| `PreviewMode` | `PREVIEW_MODE` | `"full"` | full/keys_only | M-2.8 |

配置段：`[memory]`（`builder.push("memory")`）。harness `config.ts` `DEFAULT_MEMORY_CONFIG` 11 项对齐。

---

## 13. 附属 Tool 清单

| Tool id | 类 | harness |
|---------|-----|---------|
| `tlm_memory` | `MaidMemoryTool` | **完整实现**（5 action） |
| `task_priority` | `TaskPriorityTool` | stub/禁用 |

主模组内置：`use_skill`, `query_game_context`, `switch_follow_state`, `switch_work_task`, `switch_schedule`, `switch_sit`, `query_minecraft_wiki`。

---

## 14. 后台自动整理（M-2.6）

来源：`MemoryMaintenanceManager`（源码）。harness **不复制**完整维护调度（属游戏侧 tick/LLM site/主人在线判定），仅镜像可观测契约：维护模式 action 收窄（§6.3）+ 触发判定布尔式。

| 项 | 契约 |
|----|------|
| 触发 | `TIDY_ENABLED && size() >= ceil(MaxMemories * TidyThreshold) && now - lastTidyAt >= TidyCooldownMinutes 分钟 && 主人在线 ServerPlayer && LLM site 可用` |
| 时机 | `remember` 成功写盘后检查，命中则下一服务端 tick 调度 |
| 维护轮 | 设置维护标记 -> chat(tidyPrompt + archive 全量) -> LLM 调 merge -> 维护模式 action 收窄（§6.3） |
| 全出口静默 | `MaidChatBroadcastMixin`（聊天栏）+ `ChatBubbleSilenceMixin`（气泡/thinking）+ `TtsSilenceMixin`（TTS）检查维护标记拦截 |
| history 清理 | 维护结束后从 history 新端 `pollLast` 删除差值条数（维护前快照 size vs 当前） |
| 状态持久化 | `lastTidyAt` 存 JSON `meta`（§9）；owner 离线/site 不可用也更新以避免重复调度 |
| 审计 | 服务端日志 `Maid <uuid> consolidated [a, b] -> <key>` |

---

## 待核实清单

1. ✅ **Context 多行分隔符**（1.5.3 jar `#127 = ","`，无空格）。已核实。
2. ✅ **HistorySummary 触发与文案**（48K token，保留 32，1600 截断，第二条 system）。详见 §2.1。
3. ✅ **Parameter 数组**（`ArrayParameter.class` 存在，M-2.5 merge.keys 用数组）。
4. ✅ **history 容量**（512 硬编码，无 `MAID_MAX_HISTORY_LLM_SIZE`）。详见 §2.1。
5. **`getToolCallSignature` 拼接**：`name|args`，args 经 `deleteWhitespace`。
6. **Gson pretty 缩进**：2-space（Gson 惯例）；golden 语义比对。
7. **主模组 system 设定生成**（PAPI/角色卡）：不在本契约范围。
8. ✅ **`addUserHistory` 剥离 context**：存原文，不残留 `<context>`。

---

## 15. M-2.0 前置核实回填（1.5.3 jar）

| # | 核实项 | 结论 | 来源 |
|---|--------|------|------|
| V1 | `buildMessage` 签名/插入点 | `private List<LLMMessage> buildMessage(String, EntityMaid, CappedQueue)`；注入点 setting 后 summary 前 | jar；M-2.4 已实施 |
| V2 | history 增删 API | `getDeque()` LinkedBlockingDeque 可 pollFirst/remove | jar；M-2.6 已实施 |
| V3 | Parameter 数组 | ✅ `ArrayParameter.class` 存在 | 1.5.3 jar |
| V4 | 输出通道 | 4 通道（气泡/TTS/thinking/失败）；mixin 已登记 | jar；M-2.4/2.6 已实施 |
| V5 | tick 调度 chat() | 须主线程非 tool 回调内重入 | jar；M-2.6 已实施 |
| V6 | history 容量压缩 | ✅ 512 硬上限 + 48K token 压缩 | 1.5.3 jar `javap` |
| V7 | context 分隔符 | ✅ `,`（无空格）；计划初稿 `", "` 系误已更正 | 1.5.3 jar `javap -v` |
