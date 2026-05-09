> generated_by: nexus-mapper v2
> verified_at: 2026-05-09
> provenance: file tree inspection only; tests not executed

# 测试覆盖分析

## 静态测试面

| 测试目录 | 状态 | 说明 |
|----------|------|------|
| `src/test/java/` | 空目录 | 无任何测试类 |
| `src/test/resources/` | 空目录 | 无测试资源 |

## 证据缺口

**整体评估：本项目当前无自动化测试**

`src/test/` 目录结构存在（符合 Maven/Gradle 标准布局），但未包含任何测试文件。19 个 Java 源文件均无对应的单元测试或集成测试。

## 关键风险模块（按复杂性排序）

| 模块 | 行数 | 测试覆盖 | 风险 |
|------|------|----------|------|
| PriorityContainerGui | 254 | 无 | **高** — GUI 逻辑最复杂，分页、排序、预设操作 |
| TaskPriorityManager | 235 | 无 | **高** — JSON 序列化/反序列化、文件 I/O |
| ChatCommand | 222 | 无 | **中** — 多条命令路径、多种匹配逻辑 |
| ChatBarHandler | 92 | 无 | **中** — 正则解析、事件流控制 |
| MaidFinder | 92 | 无 | **中** — 名称匹配算法、距离计算 |
| TaskAutoSwitchHandler | 90 | 无 | **中** — tick 循环、冷却逻辑、任务切换 |
| TaskPriorityPreset | 89 | 无 | **中** — 排序算法、边界检查 |
| TaskPriorityTool | 156 | 无 | **中** — 3 种 action 分支、参数验证 |

## 建议

如需增加测试，优先覆盖：
1. `TaskPriorityManager` — 序列化往返测试（serialize/deserialize 一致性）
2. `MaidFinder` — 名称匹配测试（exact/fuzzy/not-found）
3. `TaskPriorityPreset` — 排序正确性测试（getSortedTasks 输出顺序）
