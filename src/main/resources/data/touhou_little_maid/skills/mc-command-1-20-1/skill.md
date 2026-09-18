---
name: mc-command-1-20-1
description: >
  Minecraft 1.20.1 command syntax wiki and anti-hallucination reference.
  DO NOT call this skill in normal conversation or for simple commands.
  ONLY call this skill when a previously attempted 'run_command' failed (e.g. syntax error, unknown argument, NBT parsing error, or execution failure) or when formulating complex 1.20.1-specific commands (execute chains, item/entity NBT, data modification, fill replacement) and you need to verify 1.20.1 version syntax boundaries.
---

# Minecraft 1.20.1 指令语法速查与避坑指南 (Command Wiki)

本指南专门用于纠正大语言模型在生成 Minecraft 1.20.1 指令时常见的版本混淆与语法幻觉。

---

## 一、1.20.1 版本断代红线（绝对禁区）

### 1. 严禁使用 1.20.5+ 的数据组件 (Data Components) 语法
- **错误（高版本组件语法）**：
  - `give @s diamond_sword[custom_name='{"text":"神剑"}']` ❌
  - `give @s bow[enchantments={levels:{power:5}}]` ❌
  - `give @s potion[potion_contents={potion:"swiftness"}]` ❌
  - `give @s diamond_sword[unbreakable={}]` ❌
- **正确（1.20.1 纯 NBT 大括号语法）**：
  - `give @s diamond_sword{display:{Name:'{"text":"神剑"}'}} 1` ✔
  - `give @s bow{Enchantments:[{id:"minecraft:power",lvl:5s}]} 1` ✔
  - `give @s potion{Potion:"minecraft:swiftness"} 1` ✔
  - `give @s diamond_sword{Unbreakable:1b} 1` ✔

### 2. 严禁使用 1.20.1 尚未加入的指令与子命令
- `/tick`（1.20.3+ 加入，1.20.1 中完全不存在）❌
- `/transfer`（1.20.5+ 加入，1.20.1 中完全不存在）❌
- `/rotate`（1.21.2+ 加入，1.20.1 中完全不存在）❌
- `/execute if items ...`（1.20.5+ 加入，1.20.1 中不存在）❌
- `/execute (if|unless) function ...`（1.20.3+ 加入，1.20.1 中不存在）❌

### 3. 严禁使用 1.12- 旧版废弃语法
- 严禁数字物品 ID（如 `give @s 276 1 0` ❌），必须使用命名空间 ID（如 `minecraft:diamond_sword` ✔）。
- 严禁旧版 `/execute @p ~ ~ ~ detect ...` ❌，必须使用 Brigadier 树状结构。
- 严禁 `/testfor`、`/testforblock`、`/blockdata`、`/entitydata` ❌，统一使用 `/execute if ...` 和 `/data`。

### 4. 原版限制：禁止直接修改玩家实体 NBT
- 执行 `/data merge entity @p {NoGravity:1b}` 或 `/data modify entity @s ...` 会被原版直接拒绝并报错："无法修改玩家数据"（Cannot modify player data）。
- 如需给玩家状态或装备，必须使用对应专用指令：`/effect`、`/attribute`、`/gamemode`、`/item replace`。

---

## 二、女仆命令执行环境契约（run_command 协同规范）

1. **执行身份固定为主人玩家**：
   - `@s` 代表**主人自己**，`~ ~ ~` 代表**主人的脚底坐标**。
   - 给主人发物品，直接使用 `give @s ...`，无需使用 `@p`。
   - 若要在女仆所在位置执行（例如在女仆脚下生成方块或清理女仆身边的怪），必须使用 `execute at` 锚定女仆：
     `execute at @e[type=touhou_little_maid:maid,limit=1,sort=nearest] run ...`
2. **严禁包含前导斜杠 `/`**：
   - `command` 参数必须是去除首字符 `/` 的纯文本命令，如 `give @s diamond 1`，不可写 `/give @s diamond 1`。
3. **单行纯文本**：
   - 不得包含换行符 `\n` 或制表符 `\t`。
4. **硬黑名单命令（调用即永久拒绝）**：
   - `tlmchat`, `tlmmemory`, `tlmautowork`, `tlm`, `tlmconfirm`。

---

## 三、高阶与复杂指令 1.20.1 标准模板

### 1. `/execute` 链式架构与跨维度传送
- **基本结构**：`execute <subcommands...> run <command>`
- **跨维度传送（1.20.1 唯一正解）**：
  `execute in minecraft:the_nether run tp @s 0 64 0`
  *（注：原版 tp 指令不支持直接输入维度名称，必须通过 execute in 切换执行维度）*
- **位置与实体锚定双拼**：
  - `as <entity>` 改变执行者（`@s` 变为目标，但位置不变）
  - `at <entity>` 改变执行位置和朝向（`@s` 不变，位置变为目标所在处）
  - 经典组合：`execute as @e[type=cow] at @s run setblock ~ ~-1 ~ minecraft:hay_block`
- **条件判断 (if / unless)**：
  - 检测方块：`execute if block ~ ~-1 ~ minecraft:diamond_block run give @s emerald 1`
  - 检测实体：`execute if entity @e[type=zombie,distance=..10] run say 附近有僵尸！`
  - 检测实体数据：`execute if data entity @s {OnGround:1b} run say 主人在地面上`
- **关系子命令 (1.20+ 原生支持)**：
  - `execute as @s on vehicle run effect give @s speed 10 1`（对坐骑应用加速）
  - `execute as @s on attacker run say 谁打我！`

### 2. 目标选择器（Selector）过滤避坑
- **距离范围必须使用双点区间记号**：
  - `distance=..10`（10格以内，**严禁写成 `<10` 或 `<=10`**）
  - `distance=10..`（10格以外）
  - `distance=5..10`（5到10格之间）
- **单实体限制 (Single Entity)**：
  - 用于仅允许单实体的参数（如 `/data get entity`、`/tp <targets> <destination>`）时，针对 `@e` 必须带上 `limit=1`，例如：`@e[type=horse,limit=1,sort=nearest]`。
- **范围清怪防误伤安全过滤（重要！）**：
  - 严禁盲目执行 `kill @e[distance=..10]`（会导致主人、女仆、掉落物、展示框一并被杀）。
  - 安全过滤范例：
    `kill @e[distance=..15,type=!player,type=!touhou_little_maid:maid,type=!item,type=!experience_orb]`
    或直接指定敌对生物：`kill @e[type=zombie,distance=..10]`。

### 3. `/give` 复杂物品与 NBT 结构
- **格式**：`give <targets> <item_id>{<NBT>} [<count>]`（数量紧跟在 NBT 大括号之后）
- **极品装备模板（包含自定义名字、Lore、附魔、无法破坏）**：
  `give @s netherite_sword{Unbreakable:1b,display:{Name:'{"text":"斩妖剑","color":"gold","bold":true}',Lore:['{"text":"一把蕴含神秘力量的神剑","color":"gray","italic":true}']},Enchantments:[{id:"minecraft:sharpness",lvl:5s},{id:"minecraft:looting",lvl:3s},{id:"minecraft:unbreaking",lvl:3s}]} 1`
  *（要点：附魔列表键名首字母大写 `Enchantments`，等级数字带短整型后缀 `s`；Name/Lore 必须是单引号包裹的 JSON 字符串；Unbreakable 带 byte 后缀 `1b`）*
- **预设药水模板**：
  `give @s potion{Potion:"minecraft:strong_healing"} 1`
  `give @s splash_potion{Potion:"minecraft:long_night_vision"} 1`
- **自定义药水效果模板**：
  `give @s potion{CustomPotionEffects:[{Id:1b,Amplifier:1b,Duration:1200}],CustomPotionColor:16711680,display:{Name:'{"text":"特制生命灵药"}'}} 1`

### 4. `/summon` 自定义实体与装备
- **格式**：`summon <entity_id> [<pos>] [<nbt>]`
- **携带装备的自定义怪物模板**：
  `summon zombie ~ ~ ~ {CustomName:'{"text":"守卫队长","color":"red"}',CustomNameVisible:1b,Health:40f,Attributes:[{Name:"generic.max_health",Base:40f}],HandItems:[{id:"minecraft:diamond_sword",Count:1b},{}],ArmorItems:[{},{},{},{id:"minecraft:diamond_helmet",Count:1b}]}`
  *（要点：手持/盔甲物品里的数量必须是 `Count:1b`，首字母大写且带 `1b` 后缀，小写 `count` 会失效导致不穿戴）*

### 5. `/fill` 与 `/setblock` 状态与替换过滤
- **方块状态用中括号 `[]`，方块实体 NBT 用大括号 `{}`**：
  `setblock ~ ~ ~ chest[facing=north]{Items:[{Slot:0b,id:"minecraft:diamond",Count:64b}]} replace`
- **范围替换过滤（注意新方块在前，被替换方块紧跟在 `replace` 之后）**：
  `fill ~-5 ~-1 ~-5 ~5 ~-1 ~5 stone replace dirt`（将区域内的泥土替换为石头）
- **清空指定立方体区域**：
  `fill ~-5 ~0 ~-5 ~5 ~5 ~5 air replace`

### 6. `/damage` 伤害指令 (1.19.4+ 原生)
- **格式**：`damage <target> <amount> [<damageType>] [at <location>] [by <entity>] [from <cause>]`
- **范例**：
  `damage @e[type=zombie,distance=..8] 15 minecraft:player_attack by @s`（以主人的名义对 8 格内僵尸造成 15 点玩家攻击伤害）

### 7. `/effect` 状态效果
- **格式**：`effect give <targets> <effect> [<seconds>] [<amplifier>] [<hideParticles>]`
- **范例**：
  `effect give @s minecraft:night_vision 300 0 true`（给主人 5 分钟夜视 I 级，隐藏粒子）
  `effect clear @s minecraft:poison`（清除指定负面效果）
  *（注：amplifier 为实际等级减 1，0 为 I 级，1 为 II 级）*

### 8. `/item` 槽位直接修改
- **格式**：`item replace entity <targets> <slot> with <item>{<nbt>} [<count>]`
- **范例**：
  `item replace entity @s armor.head with minecraft:netherite_helmet{Unbreakable:1b} 1`

---

## 四、指令失败常见报错自愈对照表

| 常见错误返回信息 | 根本原因 | 1.20.1 修正方案 |
|------------------|----------|-----------------|
| `Expected ']' but got ...` 或 `Expected '}'` | 误用了 1.20.5+ 的数据组件语法 `item[custom_name=...]` | 改回标准 NBT 大括号格式 `item{display:{Name:'{"text":"..."}'}}` |
| `Unknown or incomplete command` | 遗漏了必要子命令（如 `execute in` 后面漏了 `run`），或使用了高版本独占指令（如 `/tick`） | 补全 `run`；或使用 1.20.1 现有机制替代 |
| `Only one entity is allowed...` | 指令要求单目标，但选择器可能匹配多个实体 | 在目标选择器中添加 `limit=1`（如 `@e[...,limit=1,sort=nearest]`） |
| `Cannot modify player data` | 试图对玩家使用 `/data merge entity` 或 `/data modify` | 改用 `/effect`、`/attribute`、`/item replace`、`/gamemode` 等玩家允许指令 |
| 命令执行无任何输出且未生效 | 选择器范围条件不满足（如误写了 `<10` 或超出了距离） | 检查选择器语法，将范围改用 `distance=..10` 双点记号 |
