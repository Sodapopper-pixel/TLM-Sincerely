# 多工作模式 — 开发文档

## 功能概述

为女仆提供可配置的多工作模式排序系统，实现条件触发式自动任务切换。

**核心能力**：
- 玩家通过 GUI 配置任务优先级列表（1-10，数字越小优先级越高）
- 服务端定时扫描，当高优先级任务条件满足时自动切换
- 支持多预设切换、保存、命名（JSON 持久化）
- AI 可通过 Tool 查询和设置优先级
- 所有已注册任务（含附属 mod 注册的）均可参与排序
- 界面总开关控制功能启用/禁用

---

## 架构与数据流

```
┌─────────────────────────────────────────────────────────────┐
│                      客户端                                  │
│  ┌───────────────────┐    ┌──────────────────────────────┐  │
│  │ MaidSideTabsMixin │───>│    TaskPriorityScreen        │  │
│  │  (侧边栏追加按钮)   │    │  - 双列布局                    │  │
│  │  index=2          │    │  - 点击启用/移除任务            │  │
│  └───────────────────┘    │  - 滚轮调节优先级                │  │
│                           │  - 预设管理（增/删/改/切）       │  │
│                           │  - 主模组 TASK 纹理按钮          │  │
│                           └──────────┬───────────────────┘  │
│                                      │ 读/写                │
│                          ┌───────────▼───────────────────┐  │
│                          │  TaskPriorityManager (全局)    │  │
│                          │  config/tlm_sincerely/         │  │
│                          │  task_priority_presets.json    │  │
│                          └───────────────────────────────┘  │
├─────────────────────────────────────────────────────────────┤
│                      服务端                                  │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              TaskAutoSwitchHandler                    │   │
│  │  - 每 20 tick 轮询所有维度                            │   │
│  │  - 遍历优先级列表，找第一个 isEnable() 为 true 的任务  │   │
│  │  - 与当前任务不同且冷却结束 → maid.setTask()          │   │
│  │  - UUID 粒度冷却（默认 100 tick）                     │   │
│  │  - 配置开关：PriorityConfig.ENABLED                   │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                TaskPriorityTool (AI Tool)              │   │
│  │  - action=query     → 查询当前优先级配置               │   │
│  │  - action=set       → 设置指定任务的优先级（1-10）     │   │
│  │  - action=switch_preset → 切换到指定预设               │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 数据流时序

```
玩家点击侧边栏按钮
  → MaidSideTabsMixin 拦截 getTabs() 返回值
  → 反射读取 rightPos/topPos 构造 MaidSideTabButton
  → onClick → Minecraft.setScreen(new TaskPriorityScreen(maid))
  → 用户操作 → 调用 TaskPriorityManager API → 写入 JSON

服务端每 20 tick
  → TaskAutoSwitchHandler.onServerTick()
  → 遍历所有 ServerLevel → 过滤 Activity==WORK 的 EntityMaid
  → 检查冷却 → 找最高优先级可用任务 → maid.setTask()
```

---

## 文件清单

### 核心逻辑层 (`priority/`)

| 文件 | 职责 |
|------|------|
| `TaskPriorityPreset.java` | 预设数据类：名称、优先级映射、同优先级排序 |
| `TaskPriorityManager.java` | 全局管理器：JSON 读写、预设 CRUD、API 访问 |
| `TaskAutoSwitchHandler.java` | 服务端自动切换：定时轮询、条件匹配、冷却机制 |

### Mixin 层 (`mixin/`)

| 文件 | 目标类 | 注入方法 | 功能 |
|------|--------|---------|------|
| `MaidSideTabsMixin.java` | `MaidSideTabs` | `getTabs()` @ RETURN | 在侧边栏 index=2 追加优先级按钮 |

### GUI 层 (`client/gui/`)

| 文件 | 职责 |
|------|------|
| `TaskPriorityScreen.java` | 独立 Screen：双列布局、主模组风格按钮、滚轮调节 |
| `widget/PrioritySideTabButton.java` | 自定义侧边栏按钮（独立纹理） |

### AI 层 (`ai/tool/`)

| 文件 | 职责 |
|------|------|
| `TaskPriorityTool.java` | AI Tool：查询/设置优先级、切换预设 |

### 配置层 (`config/subconfig/`)

| 文件 | 职责 |
|------|------|
| `PriorityConfig.java` | ForgeConfigSpec 配置：启用开关、冷却时间 |

---

## 核心 API 参考

### TaskPriorityPreset

```java
// 构造
new TaskPriorityPreset("预设名称")

// 设置/移除任务优先级
preset.setPriority(taskId, 5);    // 1-10，数字越小优先级越高
preset.removeTask(taskId);
preset.hasTask(taskId);

// 获取排序后的任务列表（先按 priority 升序，再按加入顺序）
List<ResourceLocation> sorted = preset.getSortedTasks();

// 同优先级内调整顺序
preset.moveTaskUp(taskId);
preset.moveTaskDown(taskId);
```

### TaskPriorityManager

```java
// 初始化（首次调用自动加载 JSON）
TaskPriorityManager.loadPresets();

// 预设管理
List<String> names = TaskPriorityManager.getPresetNames();
TaskPriorityPreset preset = TaskPriorityManager.getActivePreset();
TaskPriorityManager.setActivePreset("预设名");
TaskPriorityManager.addPreset(preset);
TaskPriorityManager.removePreset("预设名");
TaskPriorityManager.renamePreset("旧名", "新名");

// 优先级操作（操作当前 active 预设）
int priority = TaskPriorityManager.getTaskPriority(taskId);
TaskPriorityManager.setTaskPriority(taskId, 5);
TaskPriorityManager.removeTaskPriority(taskId);

// 持久化
TaskPriorityManager.savePresets();
```

### TaskAutoSwitchHandler

- **触发**：`TickEvent.ServerTickEvent`（END 阶段）
- **频率**：每 20 tick（1 秒）检查一次
- **冷却**：每只女仆 UUID 粒度，默认 100 tick（5 秒）
- **条件**：`PriorityConfig.ENABLED.get() == true`
- **匹配**：顺序遍历 `preset.getSortedTasks()`，取第一个 `IMaidTask.isEnable(maid) == true` 的任务
- **切换**：仅当最佳任务与当前任务不同时执行 `maid.setTask(bestTask)`

---

## Mixin 详解

### MaidSideTabsMixin

```
@Mixin(MaidSideTabs.class)
注入点: getTabs() → @At("RETURN"), cancellable = true, remap = false
```

**为什么用反射而非 @Shadow**：`MaidSideTabs` 的 `rightPos`/`topPos` 字段是 `private final`，且是主模组自定义字段（无 SRG 映射），`@Shadow` 无法定位映射文件，会报 `Unable to locate obfuscation mapping`。改用 `getDeclaredField().getInt()` 反射读取。

**按钮参数计算**：
```
SPACING = 25
index = 2（TASK_BOOK=0, GLOBAL_CONFIG=1 之后）
button X = rightPos
button Y = topPos + 2 * 25 = topPos + 50
top(纹理) = 2 * 25 = 50 → V_OFFSET + 50 = 107 + 50 = 157
```

**按钮行为**：点击 → `Minecraft.getInstance().setScreen(new TaskPriorityScreen(screen.getMaid()))`，不走网络消息，纯客户端操作。

### 放弃的方案：MaidTabsMixin + EntityMaidGuiMixin

**原方案**：在标签栏（MaidTabs.getTabs()）追加按钮 → 点击发送 `ToggleTabMessage(entityId, TAB_INDEX_PRIORITY)` → 服务端 `EntityMaid.openMaidGui(player, 5)` 拦截 → 返回自定义 Container。

**放弃原因**：
1. 标签按钮会挤压原有三个标签（main、task_config、maid_config），位置冲突
2. `EntityMaid.openMaidGui` 有两个重载，`remap = false` 下 Mixin 无法区分，始终匹配单参数版本
3. Container 系统会生成 36 个玩家物品栏槽位，配置界面不应操作物品

---

## TaskPriorityScreen 布局

```
┌───────────────────── 256×256 ─────────────────────┐
│  [启用多工作模式: 开/关] [预设名] [<] [>]          │  ← 第一行 (y+4)
│                            [新建] [删除]           │  ← 第二行靠右 (y+20)
│                                                     │
│  ┌─── 未排序 ──────────┐┃┌─── 已排序 ──────────┐   │
│  │ [图标] + 任务名称    │┃│数字 [图标] 任务名称  │   │  ← 任务列表 (y+44)
│  │ [图标] + 任务名称    │┃│数字 [图标] 任务名称  │   │     每行 19px
│  │ ... 最多 10 行      │┃│... 最多 10 行       │   │
│  │   ▲   ▼  (翻页)     │┃│   ◀   ▶   (翻页)    │   │  ← 翻页按钮
│  └─────────────────────┘┃└─────────────────────┘   │
│              (分隔线 x=125)                         │
└─────────────────────────────────────────────────────┘
```

**左右列差异**：
- **左列（未排序）**：绿色 `+` 前缀，点击将任务加入当前预设（默认优先级 10）
- **右列（已排序）**：橙色数字前缀，点击循环切换优先级（1→2→...→10→1）

**滚轮调节**：鼠标在右列区域内滚动 → 计算所在行 → 循环调节对应任务优先级

**排序按钮**：右侧每个任务旁有 ▲▼ 按钮，点击可上下移动顺序（操作 order 列表）

**右键移除**：右键点击右侧任务按钮 → 从预设中移除该任务

**实时保存**：所有修改（优先级、顺序、添加/移除）立即写入 JSON，无需手动保存

**按钮渲染**：使用主模组 TASK 纹理 `textures/gui/maid_gui_task.png`，坐标 (93, 28/48, 20)，与主模组 TaskButton 风格一致

---

## 配置文件格式

### config/tlm_sincerely-common.toml

```toml
[multi_task]
    # Enable multi-task mode for maids
    Enabled = false
    # Polling interval in ticks for task switching (20 ticks = 1 second)
    PollInterval = 100
```

### config/tlm_sincerely/task_priority_presets.json

```json
{
    "active": "默认预设",
    "presets": [
        {
            "name": "默认预设",
            "priorities": [
                { "task": "touhou_little_maid:attack", "priority": 1 },
                { "task": "touhou_little_maid:farming", "priority": 3 }
            ],
            "order": [
                "touhou_little_maid:attack",
                "touhou_little_maid:farming"
            ]
        }
    ]
}
```

---

## AI Tool 接口

Tool ID: `task_priority`

### action=query

查询当前激活预设的优先级配置。返回格式：
```
Current task priority (preset: 战斗优先):
  1. [1] touhou_little_maid:attack
  2. [3] touhou_little_maid:farming
Available presets: 战斗优先, 农业优先
```

### action=set

| 参数 | 类型 | 说明 |
|------|------|------|
| `task_id` | String (ResourceLocation) | 任务 ID，枚举值为所有已注册任务 |
| `priority` | Integer | 1-10，数字越小优先级越高 |

### action=switch_preset

| 参数 | 类型 | 说明 |
|------|------|------|
| `preset_name` | String | 预设名称，枚举值为所有已存在预设 |

---

## 注意事项与已知问题

### Mixin 兼容性
- `MaidSideTabsMixin` 依赖反射读取私有字段。若主模组 `MaidSideTabs` 重构了字段名（`rightPos`/`topPos`），反射会抛异常并 fallback 到 0，按钮位置异常
- `compatibilityLevel` 必须为 `JAVA_8`（Mixin 0.8.5 上限 `JAVA_13`）

### TaskAutoSwitchHandler
- 使用 `AABB(level.getSharedSpawnPos()).inflate(256)` 做粗略筛选。在超大型世界中，如果女仆距离世界出生点超过 256 格，将不会被扫描到
- 冷却使用内存 `HashMap<UUID, Long>`，服务重启后冷却重置
- 仅对 `Activity.WORK` 模式女仆生效（日程安排中的"工作"时段）

### TaskPriorityScreen
- 作为独立 `Screen`（非 `ContainerGui`），关闭时不会触发 `Container.removed()` 等容器生命周期
- 预设名称编辑框使用中文字符渲染可能有宽度问题
- `TaskEntryButton` 构造函数 `super(x, y, w, h, Component.empty(), b -> {}, DEFAULT_NARRATION)` 中绑定了空 `onPress`，实际行为由 `onClickAction` field 在 `onPress()` override 中执行

### 已知问题
| 问题 | 描述 |
|------|------|
| 滚轮优先级焦点偏移 | 右侧列滚轮修改优先级时，行号计算基于 `taskStartY`，但鼠标坐标可能因 GUI 缩放产生偏差，导致修改的不是光标正下方的任务 |

### 数据持久化
- JSON 文件路径由 `FMLPaths.CONFIGDIR` 确定，客户端/服务端路径可能不同
- 在多玩家环境中，每个客户端独立维护自己的预设文件，服务端不共享
- `savePresets()` 在每次修改后立即调用，无批量写入优化

---

## 扩展点

### 未来可改进方向
1. **GUI 拖拽排序**：当前支持点击循环切换优先级和加减顺序，可扩展为拖拽行交换
2. **服务端预设共享**：将预设存储从客户端 JSON 迁移至女仆 NBT 数据（`TASK_DATA_SYNC`），支持跨客户端同步
3. **条件规则系统**：除 `isEnable()` 外，支持玩家自定义条件（如"白天优先农业""有怪物优先战斗"）
4. **预设导入/导出**：支持分享预设配置
5. **滚轮焦点修复**：修复 GUI 缩放导致的行号计算偏差问题
