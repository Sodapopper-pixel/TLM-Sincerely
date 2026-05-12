# AGENTS.md

## 语言规范

- 默认使用简体中文回答
- 代码、命令、报错、API 名称保持原文，不要强行翻译
- 提问澄清和git提交时也使用中文

---

## 项目信息

- **模组名称**：《车万女仆：真心为你》
- **Mod ID**: `tlm_sincerely`
- **平台**: Forge 1.20.1
- **Java**: 17
- **主模组依赖**: touhou_little_maid ≥ 1.5.1
- **开发者**: terk

---

## 相关文档

- AGENTS.md ：本文档
- DEV_PLAN.md ：开发计划，包括各类信息入口
- DEVELOPMENT.md ：**重要⚠️**，本项目开发注意事项
- wiki-reference\ ：车万附属开发指南
- .kilo\plans ：会话临时计划文件

---

## 开发规范

**重要约束**：
- **不自启动 runClient**：每次修改完成后，需要用户明确要求才能执行 `runClient`
- **不自 git 提交**：需要用户明确要求才能执行 `git commit`
- **不自运行测试**：构建成功即视为完成，测试由用户决定

---

## nexus-map 知识库使用规则

如果仓库中存在 `.nexus-map/INDEX.md`，先阅读它，然后在执行任务前读完其路由块中列出的所有文件。

如果 `.nexus-map/` 不存在，且当前任务涉及跨模块修改或接口变更，先向用户提议运行 nexus-mapper；若用户需立即开始，至少先运行 query_graph.py --summary 建立结构感知。

当任务改变了项目的结构认知（系统边界、入口、依赖关系），在交付前评估是否需要更新 .nexus-map。

---