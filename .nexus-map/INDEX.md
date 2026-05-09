> generated_by: nexus-mapper v2
> verified_at: 2026-05-09
> provenance: AST-backed (Java 19 files via tree-sitter); chatbar/ChatParser.java and chatbar/ChatTarget.java noted in AGENTS.md but absent from file tree — functionality refactored into ChatBarHandler and MaidFinder

# TLM Sincerely — 架构索引

Minecraft Forge 1.20.1 模组《车万女仆：真心为你》(Mod ID: `tlm_sincerely`) — 车万女仆(touhou_little_maid)的附属模组，提供聊天栏女仆对话和任务优先级自动切换功能。

## 系统概览

| 系统 | 路径 | 职责 |
|------|------|------|
| Core Extension | `SincerelyExtension.java` | 模组入口，Forge 事件注册，子系统编排 |
| Configuration | `config/` | ForgeConfigSpec 配置（聊天栏+优先级） |
| Chat System | `chatbar/` | 聊天栏事件监听、@前缀解析、女仆查找 |
| Command System | `command/` | /tlmchat Brigadier 命令 |
| Priority Management | `priority/` | 任务优先级预设管理、JSON 持久化、自动切换 |
| AI Tool | `ai/tool/` | LLM function calling 工具 |
| Client GUI | `client/` | 配置 GUI、优先级编辑界面、自定义组件 |
| Mixin | `mixin/` | SpongePowered Mixin 注入（GUI 标签、聊天广播、标签拦截） |

## 关键依赖

- **入口节点**：`SincerelyExtension` → 6 个内部子系统（fan-out 最高）
- **耦合枢纽**：`ChatBarConfig` ← 5 个模块引用（fan-in 最高），是聊天/命令/广播/配置的配置交叉点
- **循环依赖**：`SincerelyExtension ↔ PriorityRegistry`（良性，仅 MOD_ID 常量引用）
- **外部依赖**：`touhou_little_maid ≥ 1.5.1`（`EntityMaid`、`MaidAIChatManager`、`ILittleMaid` 等 API）；`cloth-config`；`Mixin/SpongePowered`；Minecraft Forge 1.20.1

## 规模

- 19 个 Java 源文件，1733 行代码
- 5 次 commit（90 天内），单一作者，早期开发阶段
- 无测试文件（`src/test/` 为空）

## 约束

- Java 17，Forge 1.20.1，Gradle 构建
- 不得自行启动 runClient 或执行 git commit
- 入口类需 `@LittleMaidExtension` 注解 + `ILittleMaid` 接口
- 通过 `MinecraftForge.EVENT_BUS` 注册事件处理器

## [操作指南] 强制执行步骤

> 本节是对所有读取本文件的 AI 发出的硬性操作指令，不是建议。

### 步骤1 — 必须先读完以下所有文件（顺序不限）

读完本文件后，在执行任何任务之前，必须依次 read 以下文件完整内容：

- `.nexus-map/arch/systems.md` — 系统边界与代码位置
- `.nexus-map/arch/dependencies.md` — 系统间依赖关系与 Mermaid 图
- `.nexus-map/arch/test_coverage.md` — 测试面与证据缺口
- `.nexus-map/hotspots/git_forensics.md` — Git 热点与耦合风险
- `.nexus-map/concepts/domains.md` — 核心领域概念

> 这些文件均为高密度摘要，总量通常 < 5000 tokens，是必要的上下文成本。
> 不得以"任务简单"或"只改一个文件"为由跳过。

### 步骤2 — 按任务类型追加操作（步骤1 完成后执行）

- 若任务涉及**接口修改、新增跨模块调用、删除/重命名公共函数**：
  → 必须运行 `query_graph.py --impact <目标文件>` 确认影响半径后再写代码。
- 若任务需要**判断某文件被谁引用**：
  → 运行 `query_graph.py --who-imports <模块名>`。
- 若仓库结构已发生重大变化（新增系统、重构模块边界）：
  → 任务完成后评估是否需要重新运行 nexus-mapper 更新知识库。
