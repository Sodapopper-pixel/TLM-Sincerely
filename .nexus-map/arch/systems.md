> generated_by: nexus-mapper v2
> verified_at: 2026-05-09
> provenance: AST-backed for Java (19 files); file tree manual inspection for resource/config files

# 系统边界与代码位置

## 1. Core Extension (`core-extension`)

- **路径**: `src/main/java/com/github/tartaricacid/tlm_sincerely/SincerelyExtension.java` (57 行)
- **类型**: 单文件入口类
- **职责**: 通过 `@Mod` + `@LittleMaidExtension` 注解实现 `ILittleMaid` 接口，在构造函数中完成所有子系统初始化
- **关键方法**:
  - `SincerelyExtension()` — 事件总线注册、配置注册、菜单类型注册
  - `registerAITool(ToolRegister)` — AI 工具注册入口
  - `onRegisterCommands(RegisterCommandsEvent)` — 命令注册
  - `ClientEvents.onClientSetup(FMLClientSetupEvent)` — 客户端 GUI 注册
- **依赖**: 直接导入 6 个内部模块 + Forge/主模组 API
- **被依赖**: `PriorityRegistry`（引用 `MOD_ID` 常量）

## 2. Configuration (`configuration`)

- **路径**: `src/main/java/com/github/tartaricacid/tlm_sincerely/config/`
- **文件**:
  - `GeneralConfig.java` — 配置编排入口，组合 ChatBarConfig + PriorityConfig
  - `subconfig/ChatBarConfig.java` — 聊天栏配置项（CHAT_MODE, GLOBAL_VISIBLE, REQUIRE_PREFIX, AUTO_CHAT_RANGE, PREFIX_PATTERN）
  - `subconfig/PriorityConfig.java` — 多工作模式配置项（ENABLED, COOLDOWN）
- **技术**: ForgeConfigSpec + Configured 模组 GUI
- **被引用**: 5 个模块导入 ChatBarConfig（最高 fan-in）

## 3. Chat System (`chat-system`)

- **路径**: `src/main/java/com/github/tartaricacid/tlm_sincerely/chatbar/`
- **文件**:
  - `ChatBarHandler.java` (92 行) — `@Mod.EventBusSubscriber`，处理 `ServerChatEvent`，支持 @前缀 + 无前缀自动匹配
  - `MaidFinder.java` (92 行) — 女仆查找工具类，按名称/名称模糊/UUID 查找，提供 `FindResult` record
- **核心流程**: ServerChatEvent → 解析 @前缀（匹配 ChatBarConfig.PREFIX_PATTERN）→ MaidFinder.findByName/findNearest → EntityMaid.getAiChatManager().chat()
- **注意**: AGENTS.md 列出的 ChatParser.java 和 ChatTarget.java 已不存在，功能已整合

## 4. Command System (`command-system`)

- **路径**: `src/main/java/com/github/tartaricacid/tlm_sincerely/command/ChatCommand.java` (222 行)
- **职责**: 注册 `/tlmchat` 命令树，6 个子命令路径
- **命令结构**:
  - `/tlmchat mode [on|off]` — 聊天模式切换
  - `/tlmchat global` — 全局可见切换
  - `/tlmchat to <name> <message>` — 按名称对话（含同名提示）
  - `/tlmchat uuid <uuid> <message>` — 按 UUID 精确对话
  - `/tlmchat list` — 附近女仆列表
  - `/tlmchat <message>` — 最近女仆对话（默认）
- **依赖**: MaidFinder（女仆查找）、ChatBarConfig（配置读写）

## 5. Multi-Task Management (`multi-task-management`)

- **路径**: `src/main/java/com/github/tartaricacid/tlm_sincerely/priority/`
- **文件**:
  - `TaskPriorityPreset.java` — 多工作模式数据模型（LinkedHashMap 优先级映射 + ArrayList 排序列表）
  - `TaskPriorityManager.java` — 预设管理器（JSON 文件持久化、CRUD、序列化/反序列化）
  - `TaskAutoSwitchHandler.java` — `@Mod.EventBusSubscriber`，每 20 tick 扫描活跃女仆，按优先级自动切换任务
- **持久化**: `config/tlm_sincerely/task_priority_presets.json`（Gson 格式）
- **配置**: `config/tlm_sincerely-common.toml` → `[multi_task]` 段（Enabled, PollInterval）

## 6. AI Tool (`ai-tool`)

- **路径**: `src/main/java/com/github/tartaricacid/tlm_sincerely/ai/tool/TaskPriorityTool.java` (156 行)
- **职责**: 实现 `ITool<Result>` 接口，3 种 action（query/set/switch_preset）
- **注册**: 通过 `registerAITool` 注册到主模组 AI 系统
- **依赖**: TaskPriorityManager（预设操作）、TaskManager（获取任务索引）

## 7. Client GUI (`client-gui`)

- **路径**: `src/main/java/com/github/tartaricacid/tlm_sincerely/client/`
- **文件**:
  - `gui/ConfigScreen.java` — Cloth Config API 构建配置 GUI，含 chatbar + multi_task 两个分类
  - `gui/TaskPriorityScreen.java` — 多工作模式编辑界面（独立 Screen，非 Container）
    - 双列布局：左侧未排序任务，右侧已排序任务
    - 顶部两行控件：第一行 [总开关] [预设名] [<] [>]，第二行靠右 [新建] [删除]
    - 支持滚轮修改优先级数字（1-10 循环）
    - 支持 ▲▼ 按钮调整同优先级内顺序
    - 支持右键移除任务
    - 实时保存，无需手动保存
  - `gui/widget/PrioritySideTabButton.java` — 自定义侧边栏按钮组件
- **技术**: Cloth Config API, Minecraft GUI framework, SpongePowered Mixin

## 8. Mixin (`mixin`)

- **路径**: `src/main/java/com/github/tartaricacid/tlm_sincerely/mixin/`
- **文件**:
  - `MaidSideTabsMixin.java` — `@Mixin(MaidSideTabs.class)`，Inject getTabs 添加多工作模式侧边栏按钮
  - `MaidChatBroadcastMixin.java` — `@Mixin(ChatBubbleManager.class)`，Redirect sendSystemMessage
- **Mixin 配置**: `src/main/resources/tlm_sincerely.mixins.json`（仅 client: MaidSideTabsMixin）
