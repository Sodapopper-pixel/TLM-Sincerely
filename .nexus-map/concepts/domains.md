> generated_by: nexus-mapper v2
> verified_at: 2026-05-09
> provenance: AST-backed for code structure; domain concepts inferred from code + AGENTS.md + DEV_PLAN.md

# 核心领域概念

## 女仆 (Maid)

车万女仆模组的核心实体 `EntityMaid`，由玩家拥有（`isOwnedBy`）。本模组围绕女仆的两个核心交互：
- **对话**：通过 `MaidAIChatManager.chat()` 发送消息，支持 LLM AI 回复
- **任务**：女仆执行的工作（`IMaidTask`），通过 `setTask()` 切换

## 聊天栏模式 (Chat Bar Mode)

通过 `ChatBarConfig.CHAT_MODE` 控制的全局开关：
- **开启**：玩家在聊天栏发送消息时，自动转发给最近的女仆
- **前缀解析**：支持 `@女仆名` 语法指定目标（`ChatBarConfig.PREFIX_PATTERN` 可配置）
- **全局可见**：`ChatBarConfig.GLOBAL_VISIBLE` 控制女仆回复是否全服广播

## 任务优先级 (Task Priority)

`priority/` 模块的核心概念：
- **Preset（预设）**：`TaskPriorityPreset` 包含一组 `{任务ID → 优先级(1-10)}` 的映射
- **AutoSwitch（自动切换）**：`TaskAutoSwitchHandler` 在服务端 tick 时按预设优先级切换女仆任务
- **持久化**：预设以 JSON 格式存储在 `config/tlm_sincerely/task_priority_presets.json`

## AI 工具 (AI Tool)

`ITool` 接口实现，允许女仆的 LLM AI 通过 function calling 执行操作：
- `TaskPriorityTool` 提供 query / set / switch_preset 三种操作
- 通过 `registerAITool(ToolRegister)` 注册到主模组

## 扩展入口 (Extension Entry)

车万女仆附属模组的标准入口模式：
1. `@Mod(MOD_ID)` — Forge 模组注册
2. `@LittleMaidExtension` — 标记为车万附属
3. `implements ILittleMaid` — 实现扩展接口

## Mixin 注入

使用 SpongePowered Mixin 修改主模组行为：
- `@Mixin(TargetClass.class)` — 指定目标
- `@Inject` / `@Redirect` — 注入/重定向方法调用
- 配置文件 `tlm_sincerely.mixins.json` 声明注入点

## 配置系统

两级配置架构：
- **编译期**：ForgeConfigSpec 定义（`GeneralConfig` 编排 `ChatBarConfig` + `PriorityConfig`）
- **运行时**：储存在 `config/tlm_sincerely.toml`，通过 Configured 模组提供 GUI 编辑界面
- **Cloth Config**：`ConfigScreen` 使用 Cloth Config API 构建独立配置界面
