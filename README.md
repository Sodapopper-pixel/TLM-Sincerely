# 车万女仆：真心为你

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-62B47A?style=flat-square)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-47.x-E04E14?style=flat-square)](https://files.minecraftforge.net/)
[![Java](https://img.shields.io/badge/Java-17-ED8B00?style=flat-square)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](LICENSE)

《车万女仆：真心为你》是 [Touhou Little Maid / 车万女仆](https://www.curseforge.com/minecraft/mc-mods/touhou-little-maid) 的 Forge 1.20.1 附属模组，围绕女仆对话、工作调度和长期记忆提供增强功能。

> 当前版本：`0.1.0`
>
> Mod ID：`tlm_sincerely`
>
> 开发者：terk

## 环境与前置

| 项目 | 要求 |
|---|---|
| Minecraft | 1.20.1 |
| Mod Loader | Forge 47.x，开发环境使用 47.4.0 |
| Java | 17 |
| Touhou Little Maid | 1.5.1 或更高，开发环境使用 1.5.2 |
| Cloth Config API | 11.x，开发环境使用 11.1.136 |

## 功能

### 聊天栏女仆对话

- 在聊天栏中直接与最近的女仆对话。
- 使用 `@女仆名` 指定对话目标。
- 支持按名称、模糊名称和 UUID 查找女仆。
- 支持严格前缀模式、自动对话范围和全局/私聊显示。
- 提供 `/tlmchat` 命令管理对话模式和目标。

### 多工作优先级

- 为女仆建立可切换的工作优先级预设。
- 支持同优先级任务的手动排序。
- 使用独立 Detector 检测攻击与 `IFarmTask` 工作，不通过快速切换任务进行探测。
- 主线程分帧扫描方块并验证路径可达性，能够跳过不可达工作并选择其他可用任务。
- 使用 AVAILABLE / UNAVAILABLE / UNKNOWN 缓存、确认次数和最短保持时间减少任务横跳。
- 自动切换后任务持续阻塞时，可按默认开启的配置强制刷新一次 Brain。
- 提供默认关闭的实验性攻击抢占，仅作用于预设中已经配置的攻击任务。

### 简易记忆系统

- 为每只女仆保存独立的 JSON 键值记忆。
- 区分核心记忆与归档记忆，并将必要内容注入 AI 上下文。
- 女仆 AI 可通过 `tlm_memory` Tool 自主记录、回忆和遗忘信息。
- 提供 `/tlmmemory` 命令进行查看、设置、删除、导出与回顾。
- 支持中文女仆名称和 `uuid:<UUID>` 精确选择。

## 安装

1. 安装 Minecraft 1.20.1 与 Forge 47.x。
2. 安装 Touhou Little Maid 1.5.1+ 和 Cloth Config API 11.x。
3. 将本模组 jar 放入 Minecraft 的 `mods` 文件夹。
4. 启动游戏后通过模组配置界面或 `config/tlm_sincerely-common.toml` 调整功能。

## 常用命令

```text
/tlmchat mode [on|off]
/tlmchat global
/tlmchat <消息>
/tlmchat to <名字> <消息>
/tlmchat uuid <UUID> <消息>
/tlmchat list

/tlmmemory set <名字> <key> <value>
/tlmmemory set-core <名字> <key> <value>
/tlmmemory get <名字> <key>
/tlmmemory list <名字>
/tlmmemory forget <名字> <key>
/tlmmemory export <名字> [json|text|context]
/tlmmemory summarize <名字>
```

## 构建

需要 Java 17：

```powershell
.\gradlew.bat build --no-daemon
```

构建产物位于：

```text
build/libs/tlm_sincerely-1.20.1-forge-0.1.0.jar
```

开发环境可使用：

```powershell
.\gradlew.bat runClient --no-daemon
```

也可以双击根目录的 `runClient.bat`。项目开发约束与兼容说明见 [DEVELOPMENT.md](DEVELOPMENT.md)。

## 文档

- [开发状态与规划](DEV_PLAN.md)
- [开发注意事项](DEVELOPMENT.md)
- [聊天栏女仆对话模块](docs/聊天栏女仆对话模块.md)
- [自动切换工作模块](docs/自动切换工作模块.md)
- [工作模式切换问题排查](docs/工作模式切换问题排查.md)
- [简易记忆系统模块](docs/简易记忆系统模块.md)
- [已完成计划归档](docs/plans/)

## 配置与数据

```text
config/tlm_sincerely-common.toml
config/tlm_sincerely/task_priority_presets.json
config/tlm_sincerely/maid_memories/<maid-uuid>.json
```

## 许可证

本项目使用 [MIT License](LICENSE)。
