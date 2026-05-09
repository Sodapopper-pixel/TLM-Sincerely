> generated_by: nexus-mapper v2
> verified_at: 2026-05-09
> provenance: git_detective.py 90-day analysis; 5 commits, 1 author

# Git 热点分析

## 统计

- 分析周期：90 天
- 总提交数：5
- 贡献者：1 人
- 风险等级：所有文件均为 `low`（change < 5）

## 热点文件 Top 10（按变更次数）

| 文件 | 变更 | 风险 |
|------|------|------|
| DEV_PLAN.md | 4 | low |
| build.gradle | 4 | low |
| en_us.json | 4 | low |
| zh_cn.json | 4 | low |
| AGENTS.md | 3 | low |
| ChatCommand.java | 3 | low |
| DEVELOPMENT.md | 2 | low |
| ChatBarHandler.java | 2 | low |
| ConfigScreen.java | 2 | low |
| ChatBarConfig.java | 2 | low |

## 耦合对（co-change frequency ≥ 2）

高耦合对主要集中在文档/配置文件中：

| 文件 A | 文件 B | 同步变更 | 耦合度 |
|--------|--------|----------|--------|
| DEV_PLAN.md | build.gradle | 4 | 1.0 |
| DEV_PLAN.md | en_us.json | 4 | 1.0 |
| DEV_PLAN.md | zh_cn.json | 4 | 1.0 |
| build.gradle | en_us.json | 4 | 1.0 |
| build.gradle | zh_cn.json | 4 | 1.0 |
| en_us.json | zh_cn.json | 4 | 1.0 |
| AGENTS.md | ChatCommand.java | 3 | 1.0 |
| AGENTS.md | en_us.json | 3 | 1.0 |
| AGENTS.md | zh_cn.json | 3 | 1.0 |
| ChatCommand.java | en_us.json | 3 | 1.0 |

## 解读

1. **项目处于早期开发阶段**：仅 5 次提交，热点为文档和配置
2. **多语言同步更新模式**：`en_us.json` ↔ `zh_cn.json` 总是同步变更（耦合度 1.0），符合 i18n 惯例
3. **文档驱动开发**：`DEV_PLAN.md` 和 `build.gradle` 同时变更，表明开发计划更新时常伴随构建配置调整
4. **源代码变更频率低**：`ChatCommand.java`（3 次）是最活跃的源码文件，其次 `ChatBarHandler.java`（2 次）
5. **无跨源码文件的强耦合对**：源码文件间耦合度低，表明各模块独立性良好
