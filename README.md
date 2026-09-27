# 车万女仆：真心为你  TLM-sincerely

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=flat-square)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.x-F16436?style=flat-square)](https://neoforged.net/)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](LICENSE)

《车万女仆：真心为你》是 [Touhou Little Maid / 车万女仆](https://www.curseforge.com/minecraft/mc-mods/touhou-little-maid) 的 NeoForge 1.21.1 附属模组，围绕女仆对话、工作调度和长期记忆提供增强功能。（1.20.1 Forge 版本由 `main` 分支维护）
目前实现了：
- 在聊天栏中与女仆agent进行对话
- **核心功能**工作模式自动切换模式
- 女仆agent记忆系统
- 女仆agent指令系统

欢迎反馈 bug 与提交 pr 修复

## 未来todo（画饼）

[] 迁移至1.21.1（已完成，本分支）
[] 增加玩家视角截图功能，也可能是增加相机物品，可将截图内容发送给支持视觉的女仆agent
[] 新增物品“日记本”，绑定女仆，用于 GUI 化管理女仆的记忆
[] 新增物品“翻盖机”，绑定女仆，可唤起带输入框的聊天界面，与女仆agent沉浸式聊天

> 当前版本：`0.2.0-beta`
>
> Mod ID：`tlm_sincerely`
>
> 开发者：terk

## 环境与前置

| 项目 | 要求 |
|---|---|
| Minecraft | 1.21.1 |
| Mod Loader | NeoForge 21.1.x，开发环境使用 21.1.248 |
| Java | 21 |
| Touhou Little Maid | 1.5.3-neoforge+mc1.21.1 |
| Cloth Config API | 15.x，开发环境使用 15.0.140 |

## 功能

### 聊天栏女仆对话

- 在聊天栏中直接与最近的女仆对话。
- 使用 `@女仆名` 指定对话目标。
- 支持按名称、模糊名称和 UUID 查找女仆。
- 支持严格前缀模式、自动对话范围和全局/私聊显示。
- 提供 `/tlmchat` 命令管理对话模式和目标。

### 多工作优先级

- 预设库保存在本机（客户端）配置目录；选预设时把整份任务顺序绑定到该女仆，之后改库不会自动改已绑定的女仆，也可以把预设推送给其他玩家。
- 为女仆建立可切换的工作优先级预设，支持纯列表顺序手动排序。
- 使用独立 Detector 检测攻击与 `IFarmTask` 工作，不通过快速切换任务进行探测。
- 主线程分帧扫描方块并验证路径可达性，能够跳过不可达工作并选择其他可用任务。
- 使用 AVAILABLE / UNAVAILABLE / UNKNOWN 缓存、确认次数和最短保持时间减少任务横跳。
- 自动切换后任务持续阻塞时，可按默认开启的配置强制刷新一次 Brain。
- 提供默认关闭的实验性攻击抢占，仅作用于绑定快照中已经配置的攻击任务。
- 骑乘时仍扫描全部绑定任务，切入非当前骑乘工作前自动下车。
- 近战攻击排除弓/弩/三叉戟/御币等远程武器；弓兵需同时持有弓与箭。

### 简易记忆系统

- 为每只女仆保存独立的 JSON 键值记忆。
- 区分核心记忆与归档记忆，并将必要内容注入 AI 上下文。
- 女仆 AI 可通过 `tlm_memory` Tool 自主记录、回忆和遗忘信息。
- 提供 `/tlmmemory` 命令进行查看、设置、删除、导出与回顾。
- 支持中文女仆名称和 `uuid:<UUID>` 精确选择。

## 常用命令

所有命令可见[功能 Wiki](WIKI.md)

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

需要 Java 21：

```powershell
.\gradlew.bat build --no-daemon
```

构建产物位于：

```text
build/libs/tlm_sincerely-1.21.1-neoforge-0.2.0-beta.jar
```

开发环境可使用：

```powershell
.\gradlew.bat runClient --no-daemon
```

也可以双击根目录的 `runClient.bat`。项目开发约束与兼容说明见 [DEVELOPMENT.md](DEVELOPMENT.md)。

## 文档

- [功能 Wiki](WIKI.md)
- [开发注意事项](DEVELOPMENT.md)
- [已完成计划归档](docs/plans/)

## 配置与数据

```text
config/tlm_sincerely-common.toml
config/tlm_sincerely/auto_work_presets.json
config/tlm_sincerely/auto_work_compat.json
config/tlm_sincerely/maid_memories/<maid-uuid>.json
logs/tlm_sincerely/command_audit.log
```

## 许可证

本项目使用 [MIT License](LICENSE)。
