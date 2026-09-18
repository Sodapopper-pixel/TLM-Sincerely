# AI 命令执行工具（女仆代执行 MC 命令）计划

> 状态：已实施（2026-09-16 全部阶段完成，代理范围内验收与修复完成；实机验收 V1–V14 待用户执行）  
> 项目：《车万女仆：真心为你》  
> Mod ID：`tlm_sincerely`  
> 目标平台：Forge 1.20.1 / Java 17  
> 主模组基线：Touhou Little Maid **1.5.3**（可直接使用 1.5.2+ 的 `ITool.onCallAsync` / `ITool.trigger`；1.5.1 兼容不在本计划验证范围）  
> 计划性质：新增 AI Tool——通过对话让女仆**以主人的玩家身份**执行 Minecraft 命令  
> 编写日期：2026-09-16  
> 关联文档：`docs/聊天栏女仆对话模块.md`、`docs/plans/简易记忆系统v2计划.md`（回调链追踪与 `MaidAIChatManager.chat` 注入先例）

---

## 1. 背景与需求

现有 AI 能力可以分为两类：

- TLM 内置 Tool（`switch_work_task`、`switch_follow_state` 等）：覆盖女仆自身行为；
- 本模组 Tool（`auto_work`、`maid_memory`）：覆盖自动工作与记忆系统。

但这些 Tool 只能操作"被预先封装好的动作"。玩家想让女仆做一件没有被封装的事（拿物品、传送、清怪、修地形等）时，女仆无法通过对话完成，只能由玩家自己打开聊天栏敲命令。

本计划新增 `run_command` Tool：模型把玩家意图翻译成一条 MC 命令，由女仆**以主人（玩家）的身份**在服务端执行，并把执行结果回传给模型与主人。目标效果：

```text
主人：帮我把附近 10 格内的僵尸都清了
女仆：（调用 run_command，command = "kill @e[type=zombie,distance=..10]"）
      → 命令以主人身份执行
      → 女仆：已经帮你清掉附近的僵尸了
```

---

## 2. 已确认的产品决策

| # | 决策项 | 结论 |
|---|--------|------|
| D1 | 执行身份 | **固定为主人玩家身份**（`owner.createCommandSourceStack()`），位置、维度、`@s`、相对坐标、权限等级全部按主人；不做配置项；Tool 描述中必须向模型说明这一点 |
| D2 | 权限模型 | **继承主人真实权限**，不额外提权；提供 `MaxPermissionLevel` 上限配置（默认 4 = 不限，只能下调不能上调） |
| D3 | 主人离线 | 执行/确认时主人不在线 → **拒绝执行**（不降级为女仆权限 0） |
| D4 | 名单判定 | **双重判定**：Brigadier 解析树节点名 + 原始文本二次扫描；两者任一命中即生效 |
| D5 | 黑名单语义 | 命中即**硬拒绝**，向模型返回"该命令不可用"（回环命令无法用确认补救） |
| D6 | 确认名单语义 | 可配置的"破坏性命令强制确认"名单；命中且确认功能开启 → 需主人点击确认；确认功能**默认关闭** |
| D7 | 确认交互 | 主人聊天栏可点击 `[确认] / [取消] / [本次游戏内不再确认]` 按钮；确认消息**仅主人可见**；默认超时 60 秒，超时即取消 |
| D8 | 确认期间并发 | 该女仆有 pending 确认时，新对话请求被**拒绝**并提示先确认或取消 |
| D9 | 工具开关 | `CommandToolEnabled` 默认 **true**；暂不做每女仆单独配置 |
| D10 | 限流与截断 | 单次请求链最多 3 次命令尝试、命令长度 ≤ 1024、回传模型输出截断至 2000 字符 |
| D11 | 回执 | 实际执行的命令（成功/失败）回显到主人聊天栏；黑名单/限流等策略拒绝只回给模型，由女仆转述 |
| D12 | 审计 | 记录 `maid / owner / 命令 / 判定 / 结果码 / 摘要`（不记全文）；审计日志大小上限可配置，默认 **16 MB**，超限轮转 |
| D13 | 默认名单 | 见 4.4 的两份默认清单，均可在配置中增删 |
| D14 | 会话确认 | 点 `[本次游戏内不再确认]` 后，该主人**本次登录会话**内命中过的**同一根命令名**免确认；以登录会话为界，主人登出/离开游戏即重置（仅内存、不落盘）；默认开启，可配置关闭 |
| D15 | 非目标 | 不做每女仆开关、不做身份切换配置、不做白名单模式、不做命令历史查询、不做 GUI 配置界面（Forge Config + Cloth Config 足够） |

---

## 3. 技术事实与可行性证据

以下均已对编译基线核实（详见附录 A 的核对方式）：

| # | 事实 | 影响 |
|---|------|------|
| F1 | 本项目已有 `ITool` 接入：`SincerelyExtension.registerAITool` 注册 `AutoWorkTool` / `MaidMemoryTool` | 新增 Tool 无需新基建 |
| F2 | Tool 在服务端主线程执行：`LLMCallback.onFunctionCall` 通过 `server.submit(...)` 调度；`addToolResult` 强制主线程断言 | 命令执行天然线程安全，不需要异步切线程 |
| F3 | `Entity#createCommandSourceStack()` 为 public；`Entity#getPermissionLevel()` 默认返回 0，`ServerPlayer` 覆写为按 OP 档案计算 | 女仆自身身份权限为 0；主人身份自动携带其 OP 等级 |
| F4 | `CommandSourceStack.withSource(CommandSource)` 保留 entity / position / dimension / permissionLevel | 用记录器替换 source 即可捕获输出而不改变身份 |
| F5 | `withPermission(int)` 为**精确设定**（可升可降），`withMaximumPermission(int)` 只能下调；`hasPermission(int)` 可用于探测当前等级 | 权限上限实现为"若主人等级高于上限则下调"，绝不提升 |
| F6 | `Commands.performPrefixedCommand(stack, cmd)` 会去掉开头 `/`；内部 `performCommand` 捕获 `CommandSyntaxException` / `CommandRuntimeException` / `Exception` 并通过 `sendFailure` 回给 source，返回 0，不向外抛 | 语法错误/权限不足不会打断对话，会变成文本返回；仅 Forge `CommandEvent` 被取消且带异常等极端情况需要 try-catch 兜底 |
| F7 | `CommandSource` 接口只有 `sendSystemMessage / acceptsSuccess / acceptsFailure / shouldInformAdmins / alwaysAccepts` 五个方法 | 写一个 ~20 行的记录器即可捕获全部成功/失败文本，且 `shouldInformAdmins=false` 可避免 OP 广播刷屏 |
| F8 | Brigadier 1.1.8 提供 `ParseResults#getContext()`、`CommandContextBuilder#getNodes()/#getChild()`、`ParsedCommandNode#getNode().getName()` | 解析树判定可行；`execute ... run` 的 fork 需要沿 `getChild()` 递归（差异见 R1） |
| F9 | TLM `ITool` 1.5.2+ 提供 `onCallAsync` 与 `trigger(EntityMaid, ChatCompletion)` | 确认流程可挂起 future；配置关闭时可隐藏工具、不占 token |
| F10 | `MaidAIChatManager.chat(String, ChatClientInfo, ServerPlayer)` 已有 HEAD 注入先例（`ChatMaintenanceGuardMixin`，可 cancel + 提示） | "确认期间拒绝新对话"可直接复用该注入模式与忙线提示风格 |
| F11 | 本项目聊天入口 `ChatBarHandler` 仅对 `MaidFinder.getOwnedMaids` 的己方女仆发起对话 | sender 恒为主人，`maid.getOwner()` 作为权限锚点与实际情况一致 |
| F12 | TLM 根命令为 `/tlm`；本项目根命令为 `/tlmchat`、`/tlmmemory`、`/tlmautowork` | 回环黑名单默认值来源 |
| F13 | `FMLPaths` 只有 `GAMEDIR / MODSDIR / CONFIGDIR / FMLCONFIG`（无 LOGSDIR） | 审计日志路径用 `FMLPaths.GAMEDIR/logs/tlm_sincerely/` |

---

## 4. 详细设计

### 4.1 总体数据流

```text
玩家消息 ──► LLM ──► ToolCall(run_command, command="/give @s diamond 1")
                        │
                        ▼
              ① 规范化 + 长度/字符校验
              ② 双重名单判定（解析树 + 文本）
                 ├─ 黑名单 ──────────────► 硬拒绝，回 tool result
                 ├─ 确认名单 + 确认开启 ─► ③ 主人点击确认（仅主人可见）
                 └─ 其余 ────────────────► ④ 直接执行
                        │
                        ▼
        ⑤ owner.createCommandSourceStack()
           .withSource(recorder)
           [.withPermission(min(owner等级, 上限))]
           → Commands.performPrefixedCommand(stack, cmd)
                        │
        ⑥ 收集输出 / 结果码 → 截断 2000 字符
        ⑦ 审计日志 + 主人聊天栏回执
        ⑧ callback.addToolResult(...) → 模型继续对话
```

### 4.2 Tool 定义（`ai/tool/MaidCommandTool.java`）

| 成员 | 内容 |
|------|------|
| `id()` | `run_command` |
| `summary(maid)` | 英文，必须包含以下要点：命令以**主人玩家身份**执行（位置/维度/权限/@s 都是主人）；适合执行玩家明确要求的游戏内操作；黑名单命令不可用；命中确认名单时主人会收到确认请求、可能被取消；不要用于与女仆自身管理重复的操作（如切换工作模式已有专用工具） |
| `parameters(root, maid)` | 唯一必填参数 `command: string`，描述"完整命令文本，无需以 `/` 开头；只写一条命令，不支持换行/多行" |
| `trigger(maid, chatCompletion)` | `MaidCommandConfig.COMMAND_TOOL_ENABLED` 为 false 时返回 false（不暴露工具、不占 token） |
| `onCallAsync(...)` | 主流程：校验 → 判定 → 执行或挂起确认；返回 `CompletableFuture<LLMCallback>`，挂起路径在确认/取消/超时后完成 |
| `onCall(...)` | **安全兜底**：返回"该工具需要 TLM 1.5.2+"的 tool result，不实现无确认执行路径（避免低版本静默绕过确认） |
| `invocationSummaryComponent(...)` | 可翻译摘要：`tool.tlm_sincerely.run_command.summary`，命令截断至 40 字符 |

### 4.3 执行管线（`command/MaidCommandExecutor.java`）

1. **规范化**：`trim`、去掉开头 `/`、拒绝含 `\n`/`\r`/`\t` 或长度超过 `MaxCommandLength`(1024) 的输入，返回 `ITool.invalidParam` 风格消息。
2. **主人解析**：`maid.getOwner() instanceof ServerPlayer owner && owner.isAlive()`；否则拒绝并返回"主人不在线"。
3. **权限上限**：
   ```java
   CommandSourceStack stack = owner.createCommandSourceStack().withSource(recorder);
   int cap = MaidCommandConfig.MAX_PERMISSION_LEVEL.get();     // 默认 4
   if (cap < 4 && stack.hasPermission(cap + 1)) {
       stack = stack.withPermission(cap);                       // 只下调，不提升
   }
   ```
4. **执行**：`server.getCommands().performPrefixedCommand(stack, command)`，`try/catch (Throwable)` 兜底（Forge `CommandEvent` 取消、模组命令抛非受检异常等），异常也走失败回执与审计。
5. **记录器**（`command/CommandRecorder.java`）：
   ```java
   public final class CommandRecorder implements CommandSource {
       private final List<Component> messages = new ArrayList<>();
       @Override public void sendSystemMessage(Component c) { messages.add(c); }
       @Override public boolean acceptsSuccess() { return true; }
       @Override public boolean acceptsFailure() { return true; }
       @Override public boolean shouldInformAdmins() { return false; }   // 不广播给 OP
   }
   ```
6. **结果整理**：输出 `Component#getString` 拼接、去多余空行、截断到 `ToolResultMaxChars`(2000)；无输出时回 `Result code: N`。
7. **回执**：成功/失败均向 `owner.sendSystemMessage` 发一条含命令与结果摘要的消息（`command.tlm_sincerely.run_command.echo`）；黑名单/限流/主人离线等**未执行**的拒绝不回执，避免刷屏。
8. **审计**：写入审计日志（见 4.7）。

### 4.4 双重名单判定（`command/CommandClassifier.java`）

输入：规范化后的命令文本 + 当前 `CommandDispatcher`，输出 `ALLOW | CONFIRM | BLOCKED`（附带命中项与命中来源，写审计）。

**判定一：解析树节点名**

```java
ParseResults<CommandSourceStack> parsed = dispatcher.parse(command, dummyStack); // 仅为取节点，不入队执行
collectNodeNames(parsed.getContext());  // 递归 getNodes() + getChild()
```

- 节点名处理：小写、去 `namespace:` 前缀（同时接受带前缀写法配置项匹配）。
- `execute ... run <cmd>` 的嵌套命令节点需要沿 `getChild()` 递归收集（见 R1）；解析失败时本判定直接跳过。

**判定二：原始文本扫描**

- 按空白切分；对每个 token、以及每个 `run` token 之后的首个 token，做同样的规范化（小写、去 `/`、去命名空间前缀）后匹配名单。
- 目的是兜底覆盖 `execute` fork 解析树收集不全、以及解析失败的情况。

**优先级**：黑名单 > 确认名单 > 放行。两者均命中时按黑名单硬拒绝。

**默认黑名单（回环命令）**：

```text
tlmchat, tlmmemory, tlmautowork, tlm, tlmconfirm
```

> `tlmconfirm` 列入黑名单属于纵深防御：确认 token 为随机 UUID 且从不进入模型可见文本，模型理论上无法伪造确认。

**默认确认名单（灭世级 + 管理级，默认两档）**：

```text
kill, fill, setblock, clone, clear, datapack, function, reload, stop,
op, deop, ban, ban-ip, pardon, pardon-ip, kick, whitelist,
gamerule, difficulty, setworldspawn, worldborder, forceload, spreadplayers,
save-off, save-on
```

> 便利级（`tp / give / summon / effect / xp`）默认不加入确认名单，服主可按需添加。
> 名单匹配以**根命令名**为粒度；子命令/参数级白名单不在本期范围。

### 4.5 确认流程（`command/CommandConfirmationService.java` + `command/ConfirmCommand.java`）

**状态机**

```text
tool 调用 ─► 会话信任命中（同一登录会话内已信任该根命令）─────────────► 直接执行 ⑤
             └─ 未命中 ─► 创建 Pending(token, maidUUID, ownerUUID, command, hitName, deadlineTick, callback, toolCallId)
                          │  主人收到仅自己可见的确认消息（含 [确认]/[取消]/[本次游戏内不再确认] 按钮）
                          ├─ /tlmconfirm run <token>     → 校验 → 执行 ⑤ → complete(future)
                          ├─ /tlmconfirm trust <token>   → 校验 → 记录 (ownerUUID, hitName) 到本次登录信任集合 → 执行 ⑤ → complete(future)
                          ├─ /tlmconfirm cancel <token>  → complete(future, "已取消")
                          ├─ 超时（tick 扫描 deadline）   → complete(future, "超时取消")
                          └─ owner 退出 / 女仆死亡或被移除 / 服务器停止 → complete(future, "已取消")
```

**要点**

| 项 | 设计 |
|----|------|
| 挂起实现 | `onCallAsync` 返回 future，pending 表保存 `LLMCallback + toolCallId`；所有完成路径都在服务端线程（命中命令、tick 扫描、登出/停服事件），满足 `addToolResult` 主线程断言 |
| 确认消息 | 仅 `owner.sendSystemMessage`；`ClickEvent.Action.RUN_COMMAND` 指向 `/tlmconfirm run|cancel|trust <token>`；消息含命令原文、女仆名、剩余有效时间；`SessionConfirmationEnabled=false` 时不渲染"不再确认"按钮 |
| `/tlmconfirm` | 无 OP `requires`，内部校验：token 存在、`source.getPlayer()` 的 UUID == pending.ownerUUID、未过期、未重复处理（先移除再执行，保证一次性）；校验失败只给点击者一条错误消息 |
| 会话信任 | 信任集合 = `Map<UUID owner, Set<String> hitName>`，仅存内存、服务端线程访问；`hitName` 取分类判定命中的确认名单条目（如 `/execute run fill ...` 命中 `fill` 则记住 `fill`）；主人 `PlayerLoggedOutEvent` 或服务器停止时清空该玩家集合；黑名单与权限上限不受信任影响 |
| 气泡提示 | 挂起时 `callback.refreshWaitingChatBubble` 显示"等待主人确认：/xxx" |
| 拒绝并发聊天 | 扩展 `MaidAIChatManager.chat` HEAD 注入（与 `ChatMaintenanceGuardMixin` 共存）：该女仆存在 pending 且非内部调用时，给 sender 发 `confirm_busy` 提示并 `ci.cancel()`；注意检查 `ci.isCancelled()`，避免与维护忙线提示重复发言 |
| 超时 | deadline 使用服务器游戏刻；`SincerelyMod.onServerTick` 调用 `CommandConfirmationService.onServerTick(server)` 扫描（建议每 20 tick 一次）；`ConfirmationTimeoutSeconds` 默认 60 |
| 服务生命周期 | 沿用项目现有服务模式：`bind(server)` / `unbind(server)` / `getOrNull(server)`，在 `ServerStartedEvent` / `ServerStoppedEvent` 挂接，停止时取消全部 pending |

### 4.6 限流与截断

| 项 | 规则 |
|----|------|
| 单请求链上限 | 同一 `LLMCallback` 实例上最多 3 次命令**尝试**（含确认挂起）；超出返回"本次对话命令次数已达上限"。实现：服务端线程内 `WeakHashMap<LLMCallback, Integer>` 计数（TLM 的工具批次与后续轮次复用同一 callback 实例），键弱引用无需手动清理 |
| 命令长度 | 默认 1024 字符，超限按无效参数处理 |
| 输出回传 | 默认最多 2000 字符，超长截断并追加提示 |
| 对话轮数 | 复用 TLM 既有 16 轮工具上限与重复批次拦截，不另做 |

### 4.7 审计日志（`command/CommandAuditLog.java`）

- 路径：`<gamedir>/logs/tlm_sincerely/command_audit.log`（`FMLPaths.GAMEDIR` 拼接，`FMLPaths` 无 LOGSDIR）。
- 格式（单行、不记输出全文）：

```text
2026-09-16T17:20:31Z maid="咲夜"(uuid) owner="terk"(uuid) decision=CONFIRMED_EXECUTED result=1 cmd="/give @s diamond 1" hit=-
```

- `decision` 枚举：`EXECUTED` / `CONFIRMED_EXECUTED` / `SESSION_TRUSTED_EXECUTED` / `CANCELLED` / `TIMEOUT` / `BLOCKED_BLACKLIST` / `REJECTED_OWNER_OFFLINE` / `RATE_LIMITED` / `INVALID` / `ERROR`。
- 大小上限：`AuditLogMaxSizeMb`（默认 16，范围 1–1024）。写入后检查大小，超限则 `command_audit.log → command_audit.log.1`（覆盖旧 `.1`）后重开新文件。
- 仅在服务端线程写入（命令执行频率低，append + flush 可接受），保证顺序且不引入线程安全问题。

### 4.8 配置清单（`config/subconfig/MaidCommandConfig.java`）

配置节名 `maid_command`，在 `GeneralConfig.init` 注册，客户端在 `ConfigScreen` 增加"命令执行"子分类。

| 配置键 | 默认值 | 范围 | 说明 |
|--------|--------|------|------|
| `CommandToolEnabled` | `true` | bool | `run_command` 工具总开关（关闭后工具不对模型暴露） |
| `ConfirmationEnabled` | `false` | bool | 破坏性命令强制确认 |
| `SessionConfirmationEnabled` | `true` | bool | 允许"本次游戏内不再确认"；信任集合按登录会话保存在内存 |
| `ConfirmationTimeoutSeconds` | `60` | 10–600 | 确认超时 |
| `ConfirmationRequiredCommands` | 见 4.4 默认清单 | List\<String\> | 强制确认名单 |
| `BlacklistedCommands` | 见 4.4 默认清单 | List\<String\> | 硬拒绝名单 |
| `MaxPermissionLevel` | `4` | 0–4 | 主人权限上限，只下调不提升 |
| `MaxCommandsPerRequest` | `3` | 1–16 | 单请求链命令尝试上限 |
| `MaxCommandLength` | `1024` | 16–4096 | 命令文本长度上限 |
| `ToolResultMaxChars` | `2000` | 200–20000 | 回传模型的输出截断 |
| `AuditLogMaxSizeMb` | `16` | 1–1024 | 审计日志大小上限 |

---

## 5. 任务拆解

> 全程不执行 `runClient`、不 git 提交；每个阶段结束以 `.\gradlew.bat build --no-daemon`（Java 17）验证编译。

### 阶段 0：前置核验（低风险）

| # | 任务 | 产物/完成条件 |
|---|------|---------------|
| 0.1 | 复核 F3–F7 的原版行为 | 已有 javap 证据，落地时如遇行为差异以实际为准 |
| 0.2 | 静态核验 `execute ... run <cmd>` 在解析树中的节点收集范围（反编译 `ExecuteCommand` 与 Brigadier fork 机制），不确定处以文本兜底为准 | 结论写入 `DEVELOPMENT.md`；运行时表现并入用户验收 V4/V6 |
| 0.3 | 静态确认 `MaidAIChatManager.chat` HEAD 注入与 `ChatMaintenanceGuardMixin` 的先后顺序与 `ci.isCancelled()` 检查点 | 结论写入 `DEVELOPMENT.md` |

### 阶段 1：配置与名单判定（可与阶段 2 并行）

| # | 任务 | 涉及文件 |
|---|------|----------|
| 1.1 | 新增 `MaidCommandConfig` 并注册进 `GeneralConfig` | `config/subconfig/MaidCommandConfig.java`、`config/GeneralConfig.java` |
| 1.2 | `ConfigScreen` 增加"命令执行"子分类 | `client/gui/ConfigScreen.java` |
| 1.3 | 实现 `CommandClassifier`（规范化、解析树递归收集、文本扫描、默认名单） | `command/CommandClassifier.java` |
| 1.4 | 用样例集验证判定：`/minecraft:kill`、`/execute run kill @e`、`/execute as @a run tlmchat hi`、`/execute run tlmautowork compat report`、正常 `/give` | 日志或临时调试输出 |

### 阶段 2：执行管线

| # | 任务 | 涉及文件 |
|---|------|----------|
| 2.1 | `CommandRecorder`（输出记录、禁 OP 广播） | `command/CommandRecorder.java` |
| 2.2 | `MaidCommandExecutor`（主人解析、权限上限、执行、结果整理、异常兜底） | `command/MaidCommandExecutor.java` |
| 2.3 | `CommandAuditLog`（写入、枚举、大小轮转） | `command/CommandAuditLog.java` |
| 2.4 | 主人聊天栏回执（成功/失败） | 随 2.2 实现 |

### 阶段 3：Tool 接入

| # | 任务 | 涉及文件 |
|---|------|----------|
| 3.1 | `MaidCommandTool`（id/summary/parameters/trigger/onCallAsync/onCall 兜底/摘要组件） | `ai/tool/MaidCommandTool.java` |
| 3.2 | 单请求链限流计数（WeakHashMap 方案） | 随 3.1 或 `command/CommandRateLimiter.java` |
| 3.3 | 注册到 `SincerelyExtension.registerAITool` | `SincerelyExtension.java` |
| 3.4 | 语言键（zh_cn / en_us）：tool 摘要、确认消息、按钮、回执、忙线提示、限流提示 | `assets/tlm_sincerely/lang/*.json` |

### 阶段 4：确认流程

| # | 任务 | 涉及文件 |
|---|------|----------|
| 4.1 | `CommandConfirmationService`（pending 表、绑定生命周期、tick 超时扫描、完成 future） | `command/CommandConfirmationService.java` |
| 4.2 | `/tlmconfirm run|cancel <token>` 命令注册与校验 | `command/ConfirmCommand.java`、`SincerelyMod.onRegisterCommands` |
| 4.3 | 主人可见确认消息 + `[确认]/[取消]` 按钮 | 随 4.1 |
| 4.4 | `onCallAsync` 挂起与完成、气泡"等待确认"提示 | `ai/tool/MaidCommandTool.java` |
| 4.5 | 确认期间拒绝新对话（`chat` HEAD 注入，检查 `ci.isCancelled()`） | `mixin/CommandConfirmationChatGuardMixin.java`、`tlm_sincerely.mixins.json` |
| 4.6 | 边界清理：owner 登出（`PlayerLoggedOutEvent`）、女仆死亡/移除、服务器停止、重复点击幂等 | 随 4.1/4.2 |
| 4.7 | 会话确认：信任集合（owner + 命中命令名）、`trust` 子命令与第三个按钮、登出/停服清理、`SESSION_TRUSTED_EXECUTED` 审计 | `command/CommandConfirmationService.java`、`command/ConfirmCommand.java` |

### 阶段 5：收尾

| # | 任务 | 涉及文件 |
|---|------|----------|
| 5.1 | 新增功能文档 `docs/女仆命令执行模块.md`（功能、配置、权限模型、安全说明、排查） | 文档 |
| 5.2 | 更新 `DEV_PLAN.md`（新增章节与状态） | `DEV_PLAN.md` |
| 5.3 | 更新 `DEVELOPMENT.md` 开发笔记（execute fork 节点、注入顺序、确认挂起生命周期、日志轮转等踩坑） | `DEVELOPMENT.md` |
| 5.4 | 手动验收矩阵（第 7 节）由用户执行 | — |
| 5.5 | 将 `mods.toml` 中 `touhou_little_maid` 的 `versionRange` 由 `[1.5.1,)` 更新为 `[1.5.3,)`，与实际基线一致 | `META-INF/mods.toml` |

---

## 6. 风险与缓解

| # | 风险 | 影响 | 缓解 |
|---|------|------|------|
| R1 | `/execute ... run` 的 fork 解析树可能收集不到嵌套命令节点 | 名单漏判 | 文本兜底必须实现并覆盖 `run` 后 token；阶段 0.2 先验证实际节点链 |
| R2 | 确认挂起期间回调未完成 | 对话卡死、token 浪费 | 所有终止路径（确认/取消/超时/登出/停服/异常）都必须 complete future；tick 扫描与 unbind 双保险 |
| R3 | Forge `CommandEvent` 被其他模组取消并携带异常 | 执行抛出非受检异常 | `catch (Throwable)` 兜底，记审计 `ERROR`，返回失败文本给模型 |
| R4 | 提示注入诱导破坏性命令 | 主人资产/世界受损 | 默认继承主人权限（不放大）；确认名单；黑名单；审计；文档提醒服主不要把此工具暴露给不可信玩家 |
| R5 | 命令输出过大（`/data get`、`/list` 等） | 模型 token 爆炸、聊天刷屏 | 回传截断 2000；主人回执只显示摘要 |
| R6 | 审计日志膨胀/写盘失败 | 磁盘占用、日志丢失 | 默认 16 MB 上限 + `.1` 轮转；写失败只 WARN 不打断命令流程 |
| R7 | 名单无法穷举（新模组命令、别名） | 漏判破坏性命令 | 双重判定 + 可配置名单 + 权限继承；文档注明"名单是缓解而非沙箱" |
| R8 | 多人服务器中确认按钮被他人点击 | 越权执行 | 确认消息只发主人；`/tlmconfirm` 校验点击者 UUID == owner；token 一次性 |
| R9 | 确认挂起与记忆维护/摘要等内部调用的 `chat` 注入相互干扰 | 误拦内部调用 | 沿用现有 `isInternalMaintenanceCall` 判定的注入顺序约定；`ci.isCancelled()` 检查 |
| R10 | "本次游戏内不再确认"削弱确认防护 | 注入场景下被滥用的窗口变大 | 仅登录会话内有效、登出即清；按根命令粒度而非全局；审计记 `SESSION_TRUSTED_EXECUTED`；可用配置关闭 |

---

## 7. 验收场景（用户执行）

| # | 场景 | 预期结果 |
|---|------|----------|
| V1 | 单人开启作弊（主人权限 4），让女仆"给我一把钻石剑" | 模型调用 `run_command`，以主人身份、主人位置执行 `/give @s diamond 1` 成功；主人收到回执；审计日志有 `EXECUTED` |
| V2 | 非 OP 主人要求 `/give` | 命令以 0 级权限执行失败，模型收到失败文本，女仆转述失败原因，无副作用 |
| V3 | 要求女仆执行 `/tlmchat` 或 `/tlm` | 硬拒绝（`BLOCKED_BLACKLIST`），模型收到"命令不可用" |
| V4 | 要求 `/execute run kill @e[type=zombie]`（确认开启时） | 双重判定命中 `kill`，进入确认流程而非直接执行 |
| V5 | 确认开启，要求 `/fill` | 仅主人看到确认消息（含三个按钮）；点 `[确认]` 后执行；点 `[取消]` 或 60 秒不点则取消，模型收到取消结果 |
| V6 | 有 pending 确认时主人再发消息 | 新消息被拒绝并提示先确认/取消；确认后恢复对话 |
| V7 | 确认等待中主人退出游戏；或执行瞬间主人已离线 | pending 取消 / 直接拒绝（`REJECTED_OWNER_OFFLINE`） |
| V8 | `MaxPermissionLevel = 2`，主人为 4 级 | `/give`（2 级）成功；`/stop`（4 级）被下限拒绝 |
| V9 | 同一轮对话模型连续请求 4 条命令 | 前 3 条执行，第 4 条被限流拒绝 |
| V10 | 反复执行直到审计日志超过上限 | `command_audit.log` 轮转为 `.1`，新文件继续记录，无异常 |
| V11 | 执行 `/data get` 等大输出命令 | 回传模型内容被截断至 2000 字符，游戏无卡顿 |
| V12 | 关闭 `CommandToolEnabled` | 工具不再出现在模型工具列表；对话中女仆不会尝试执行命令 |
| V13 | 点 `[本次游戏内不再确认]` 后，同一登录会话内再次请求同类根命令（如 `fill`） | 不再弹确认、直接执行；审计为 `SESSION_TRUSTED_EXECUTED` |
| V14 | 重新登录后请求同类命令；`SessionConfirmationEnabled=false` | 重新要求确认；配置关闭时不渲染"不再确认"按钮 |
| V15 | 构建 | `.\gradlew.bat build --no-daemon` 成功（Java 17） |

---

## 8. 非目标与待定

- 不做每女仆独立开关、不做执行身份切换配置、不做命令白名单模式、不做 GUI 配置界面。
- 不做命令执行历史查询 Tool（审计日志文件由服主查看）。
- 不修改 TLM 本体；不接管原版命令权限系统。
- 已确认：`mods.toml` 依赖范围更新为 `[1.5.3,)`（任务 5.5）。
- 待定（取决于 MVP 实机效果）：子命令/参数级名单粒度；命令输出以聊天框分页/悬浮查看。

---


## 附录 A：调研核对方式

| 结论 | 核对方式 |
|------|----------|
| F3/F4/F5/F6 | 对 `forge-1.20.1-47.4.0_mapped_parchment_...-recomp.jar` 执行 `javap -p -c`，检查 `Entity#createCommandSourceStack`、`Entity#getPermissionLevel`、`CommandSourceStack#withSource/withPermission/withMaximumPermission`、`Commands#performPrefixedCommand/performCommand` 的字节码与异常表 |
| F7 | `javap net.minecraft.commands.CommandSource` |
| F8 | `javap` Brigadier 1.1.8 的 `CommandContextBuilder`、`ParsedCommandNode` |
| F9/F10/F11 | TLM 1.5.3 mapped sources：`ITool.java`、`LLMCallback.java`、`MaidAIChatManager.java`、`EntityMaid.java`；本项目 `ChatMaintenanceGuardMixin.java`、`ChatBarHandler.java`、`MaidFinder.java` |
| F12 | TLM `command/RootCommand.java`；本项目 `ChatCommand.java`、`MemoryCommand.java`、`AutoWorkCompatCommand.java` |
| F13 | `javap net.minecraftforge.fml.loading.FMLPaths`（fmlloader 1.20.1-47.4.0） |

---

## 实施进度 / 修订（2026-09-16 追加）

> 本节仅追加记录，不改写上方正文。

**状态**：全部阶段已实施；每个阶段结束均执行 `.\gradlew.bat build --no-daemon`（Java 17）通过；未执行 `runClient`（依 AGENTS.md 约定，实机验收 V1–V14 留给用户）。

**阶段产物**

| 阶段 | 结果 |
|------|------|
| 0 | 静态核验完成，结论写入 `DEVELOPMENT.md`：`execute run` 为 `literal("run").redirect(root)`，`ParseResults#getContext()` 只含外层节点，必须沿 `getChild()` 递归；`ci.isCancelled()` 为 Mixin 0.8.5 公开方法，双 HEAD 注入采用"顺序无关"的让步逻辑 |
| 1 | `MaidCommandConfig` + `GeneralConfig` 注册 + `ConfigScreen` 命令执行子分类 + `CommandClassifier`；样例集用 classpath 探针静态验证（见 DEVELOPMENT.md 开发笔记） |
| 2 | `CommandRecorder` / `MaidCommandExecutor` / `CommandAuditLog` / 主人回执 |
| 3 | `MaidCommandTool` / `CommandRateLimiter` / `SincerelyExtension` 注册 / lang 双向 |
| 4 | `CommandConfirmationService` / `ConfirmCommand` / `CommandConfirmationChatGuardMixin` / `SincerelyMod` 生命周期接线 |
| 5 | `docs/女仆命令执行模块.md`、`DEV_PLAN.md`、`DEVELOPMENT.md`、`mods.toml` `[1.5.3,)` |

**与计划的偏差（均为实现细节，不改变产品决策）**

1. `CommandAuditLog.log` 接受 `EntityMaid + @Nullable ServerPlayer`：主人离线路径需要在没有 `ServerPlayer` 的情况下记录 owner UUID/名称（写 `<offline>`）。
2. 解析树判定只收集 `LiteralCommandNode` 的名字（不收集参数节点名），避免参数名（如 `targets`）造成误判；参数级内容仍由文本扫描覆盖。
3. 确认按钮路径的取消会额外给主人发一条"已取消命令"回执（计划只要求模型侧结果）；确认/信任路径由执行回执承担反馈。
4. `awaitConfirmation` 内部再做一次 `hasPendingForMaid` 防御性检查（同一女仆已在挂起时直接返回拒绝文本），避免极端并发下出现双 pending。
5. `MaidCommandTool` 在 `onCallAsync` 分发时再次检查 `CommandToolEnabled`（`trigger` 已隐藏工具，这里防配置中途变更）。
6. `MaidCommandTool.onCallAsync` 增加"非服务端线程时经 `runOnServerThread` 重调度"的防御分支（TLM 1.5.3 实际都在服务端线程调度）。
7. `ConfirmCommand` 的 `trust` 在 `SessionConfirmationEnabled=false` 时按普通确认执行，不记录信任（按钮本身不渲染，仅防手输绕过）。
8. 额外把 `DEV_PLAN.md` 头部"主模组依赖"同步为 ≥ 1.5.3（与任务 5.5 保持一致）。

**未验证项（留给用户实机验收）**

- V1–V14 全部运行时场景（本环境不启动游戏）；其中分类判定 V4/V6、确认 UI V5/V6/V13、审计轮转 V10 依赖实机
- 确认挂起与记忆维护忙线同时命中时只会提示一条（静态推导为顺序无关，实机可复核）
- 破坏性命令确认默认关闭状态下的直接执行路径（配置默认值）

**验收后修订（子代理只读验收，2026-09-16）**

只读验收发现 1 个 major（pending 迭代与 future 内联续跑的再入风险）与若干 minor，已修复：

1. `CommandConfirmationService` 的 `onServerTick` / `onOwnerLoggedOut` / `cancelAll` 改为**先快照、再移除、再完成**：`future.complete` 会在服务端线程内联恢复 TLM 工具批次，同一批次里后续 `run_command` 可能插入新 pending，直接迭代 `pending.values()` 会触发 `ConcurrentModificationException`
2. `isTrusted` 增加 `SESSION_CONFIRMATION_ENABLED` 判断（运行中关闭该开关后存量信任立即失效）
3. `complete()` 在 `addToolResult` 抛异常时改为 `future.completeExceptionally`，由 TLM 的 `onToolErrorCall` 生成错误 tool result，避免 future 永久挂起
4. `onCallAsync` 的非服务端线程防御分支：目标侧不是 `ServerLevel` 时直接以原 callback 完成，避免 `runOnServerThread` 静默 no-op 造成永久挂起
5. 确认消息、气泡、回执与取消回执统一经 `MaidCommandExecutor.sanitizeForDisplay`，剥离 `§` 格式码与控制字符（执行的原命令不受影响）
6. `truncate` 预留后缀长度，实际长度严格 ≤ `ToolResultMaxChars`
7. `joinMessages` 收集到预算上限即停止，避免 `/data get` 等大输出先分配再截断
8. `awaitConfirmation` 的确认消息发送失败时回滚 pending 并返回失败 tool result
9. `ChatMaintenanceGuardMixin` 的 HEAD 处理器增加 `ci.isCancelled()` 早退，避免被命令确认忙线取消的对话仍登记 `beginOrdinaryChat`

**已知实现取舍（非缺陷，文档记录）**

- 限流计数位于规范化之前：非法输入也消耗一次尝试预算（更保守地防滥用）
- 分类判定为保守策略：原文扫描会剥离任意 token 的 `namespace:` 前缀，因此资源 ID 恰为名单条目（如 `foo:tlm`）会命中；这是"宁可多确认/多拒绝"的设计取舍
- 审计记录的是规范化后的命令（无前导 `/`），与 4.7 格式示例的 `cmd="/give ..."` 略有差异
- 挂起时女仆气泡含命令原文，随实体数据同步给能看见女仆的玩家（确认消息本身仍仅主人可见）
