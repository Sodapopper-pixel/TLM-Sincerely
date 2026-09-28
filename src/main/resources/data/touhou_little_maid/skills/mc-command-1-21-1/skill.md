---
name: mc-command-1-21-1
description: >
  Minecraft 1.21.1 command syntax wiki and anti-hallucination reference.
  DO NOT call this skill in normal conversation or for simple commands.
  ONLY call this skill when a previously attempted 'run_command' failed (e.g. syntax error, unknown argument, component parsing error, or execution failure) or when formulating complex 1.21.1-specific commands (execute chains, item data components, entity NBT, data modification, fill replacement) and you need to verify 1.21.1 version syntax boundaries.
---

# Minecraft 1.21.1 指令语法速查与避坑指南 (Command Wiki)

本指南专门用于纠正大语言模型在生成 Minecraft 1.21.1 指令时常见的版本混淆与语法幻觉。

---

## 一、1.21.1 版本断代红线（绝对禁区）

### 1. 物品必须使用数据组件 (Data Components) 语法，严禁 1.20.4- 的纯 NBT 大括号语法
- 1.20.5 起 `give`、`item`、`clear`、`loot` 中的物品格式已全面改为 `物品id[组件=值]`，**物品上的 NBT 大括号写法已彻底移除，写上必然报错**。
- **错误（旧版 NBT 大括号语法）**：
  - `give @s diamond_sword{display:{Name:'{"text":"神剑"}'}} 1` ❌
  - `give @s bow{Enchantments:[{id:"minecraft:power",lvl:5s}]} 1` ❌
  - `give @s potion{Potion:"minecraft:swiftness"} 1` ❌
  - `give @s diamond_sword{Unbreakable:1b} 1` ❌
- **正确（1.21.1 数据组件语法）**：
  - `give @s diamond_sword[custom_name='{"text":"神剑","color":"gold"}'] 1` ✔
  - `give @s bow[enchantments={levels:{"minecraft:power":5}}] 1` ✔
  - `give @s potion[potion_contents={potion:"minecraft:swiftness"}] 1` ✔
  - `give @s diamond_sword[unbreakable={}] 1` ✔
- **常用组件速查**：
  - 自定义名字：`custom_name='{"text":"名字","color":"gold","bold":true}'`（引号规则：外层 SNBT 单引号，内层 JSON 双引号；纯文本可简写为 `custom_name='"名字"'`）
  - Lore：`lore=['{"text":"第一行","color":"gray","italic":true}','{"text":"第二行"}']`
  - 附魔：`enchantments={levels:{"minecraft:sharpness":5,"minecraft:looting":3,"minecraft:unbreaking":3}}`（列表键名小写、等级直接写数字，**没有** `lvl` 与 `s` 后缀）
  - 药水：`potion_contents={potion:"minecraft:strong_healing"}`；自定义效果 `potion_contents={potion:"minecraft:water",custom_effects:[{id:"minecraft:speed",amplifier:1b,duration:1200}]}`
  - 无法破坏：`unbreakable={}`
- **组件语法同样适用于 `/item replace` 与 `/clear` 谓词**：
  - `item replace entity @s armor.head with netherite_helmet[unbreakable={}] 1` ✔

### 2. 严禁使用 1.21.1 尚未加入的指令与子命令
- `/rotate`（1.21.2+ 加入，1.21.1 中完全不存在）❌
- `/dialog`、`/hud` 等更高版本指令 ❌
- `/tick`（1.20.3+）、`/transfer`（1.20.5+）、`/execute if items`（1.20.5+）在 1.21.1 中**存在**，可以使用 ✔

### 3. 严禁使用 1.12- 旧版废弃语法
- 严禁数字物品 ID（如 `give @s 276 1 0` ❌），必须使用命名空间 ID（如 `minecraft:diamond_sword` ✔）。
- 严禁旧版 `/execute @p ~ ~ ~ detect ...` ❌，必须使用 Brigadier 树状结构。
- 严禁 `/testfor`、`/testforblock`、`/blockdata`、`/entitydata` ❌，统一使用 `/execute if ...` 和 `/data`。

### 4. 实体 NBT 中的物品格式已随 1.20.5+ 变更（易错点！）
- `/summon`、`/data` 中嵌套的物品（如 `HandItems`、`ArmorItems`、箱子 `Items`）使用**小写 `count` 的组件化物品格式**，旧版 `Count:1b` 大写键已失效：
  - `summon zombie ~ ~ ~ {HandItems:[{id:"minecraft:diamond_sword",count:1},{}]}` ✔（数量写 `count:1`，小写、无后缀；空槽用 `{}`）
  - `setblock ~ ~ ~ chest{Items:[{Slot:0b,id:"minecraft:diamond",count:64}]}` ✔（槽位仍是 `Slot:0b`）
- 实体属性列表键名已改为 `id`/`base` 小写：
  - `summon zombie ~ ~ ~ {Attributes:[{id:"minecraft:generic.max_health",base:40.0}]}` ✔（旧版 `{Name:"generic.max_health",Base:40f}` ❌）
- 实体的 `CustomName`、`CustomNameVisible`、`Health` 等仍是 NBT，写法不变：`CustomName:'{"text":"名字"}'`、`CustomNameVisible:1b`。

### 5. 原版限制：禁止直接修改玩家实体 NBT
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

## 三、高阶与复杂指令 1.21.1 标准模板

### 1. `/execute` 链式架构与跨维度传送
- **基本结构**：`execute <subcommands...> run <command>`
- **跨维度传送**：
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
  - 检测物品（1.20.5+，组件谓词）：`execute if items entity @s weapon.mainhand diamond_sword[enchantments~{levels:{"minecraft:sharpness":5}}] run say 手持锋利V剑`
- **关系子命令**：
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

### 3. `/give` 极品装备组件模板（1.21.1）
`give @s netherite_sword[unbreakable={},custom_name='{"text":"斩妖剑","color":"gold","bold":true}',lore=['{"text":"一把蕴含神秘力量的神剑","color":"gray","italic":true}'],enchantments={levels:{"minecraft:sharpness":5,"minecraft:looting":3,"minecraft:unbreaking":3}}] 1`
*（要点：组件名全小写；附魔是 `{levels:{id:数字}}` 结构；Name/Lore 里的 JSON 用单引号包裹的 SNBT 字符串承载；组件之间用英文逗号分隔，整体用 `[]` 包裹）*

### 4. `/summon` 自定义实体与装备
- **格式**：`summon <entity_id> [<pos>] [<nbt>]`
- **携带装备的自定义怪物模板**：
  `summon zombie ~ ~ ~ {CustomName:'{"text":"守卫队长","color":"red"}',CustomNameVisible:1b,Health:40f,Attributes:[{id:"minecraft:generic.max_health",base:40.0}],HandItems:[{id:"minecraft:diamond_sword",count:1,components:{"minecraft:enchantments":{levels:{"minecraft:sharpness":3}}}},{}}],ArmorItems:[{},{},{},{id:"minecraft:diamond_helmet",count:1}]}`
  *（要点：物品一律 `{id, count}` 小写组件格式；要给怪物的物品附魔需内嵌 `components` 映射，组件名带 `minecraft:` 命名空间）*

### 5. `/fill` 与 `/setblock` 状态与替换过滤
- **方块状态用中括号 `[]`，方块实体 NBT 用大括号 `{}`**（这与物品组件不同——方块世界的写法未变）：
  `setblock ~ ~ ~ chest[facing=north]{Items:[{Slot:0b,id:"minecraft:diamond",count:64}]} replace`
- **范围替换过滤（注意新方块在前，被替换方块紧跟在 `replace` 之后）**：
  `fill ~-5 ~-1 ~-5 ~5 ~-1 ~5 stone replace dirt`（将区域内的泥土替换为石头）
- **清空指定立方体区域**：
  `fill ~-5 ~0 ~-5 ~5 ~5 ~5 air replace`

### 6. `/damage` 伤害指令
- **格式**：`damage <target> <amount> [<damageType>] [at <location>] [by <entity>] [from <cause>]`
- **范例**：
  `damage @e[type=zombie,distance=..8] 15 minecraft:player_attack by @s`（以主人的名义对 8 格内僵尸造成 15 点玩家攻击伤害）

### 7. `/effect` 状态效果
- **格式**：`effect give <targets> <effect> [<seconds>] [<amplifier>] [<hideParticles>]`
- **范例**：
  `effect give @s minecraft:night_vision 300 0 true`（给主人 5 分钟夜视 I 级，隐藏粒子）
  `effect clear @s minecraft:poison`（清除指定负面效果）
  *（注：amplifier 为实际等级减 1，0 为 I 级，1 为 II 级）*

### 8. `/tick` 与 `/transfer`（1.21.1 可用，谨慎使用）
- `tick query` 查询当前 tick 表现；`tick rate 20` 设置 tick 频率（会改变全服游戏速度，**不建议女仆主动调用**）。
- `transfer <host> [<port>] [<players>]` 用于把玩家转移到其他服务器（模组服慎用）。

---

## 四、指令失败常见报错自愈对照表

| 常见错误返回信息 | 根本原因 | 1.21.1 修正方案 |
|------------------|----------|-----------------|
| 报错包含 `component`（组件解析失败）或 `Expected ']' but got '{'` | 在物品上误用了 1.20.4- 的 NBT 大括号语法 `item{display:...}` | 改为数据组件语法 `item[custom_name='{"text":"..."}']`；附魔用 `enchantments={levels:{...}}` |
| 报错包含 `Unknown data component` | 使用了 1.21.1 不存在的组件名（或拼错） | 核对组件名（全小写），只用本指南列出的常用组件 |
| 报错包含 `Invalid enchantment` 或附魔等级解析失败 | 附魔仍写成旧格式 `{id:"...",lvl:5s}` | 改为 `enchantments={levels:{"minecraft:sharpness":5}}` |
| `Unknown or incomplete command` | 遗漏了必要子命令（如 `execute in` 后面漏了 `run`），或使用了 1.21.2+ 独占指令（如 `/rotate`） | 补全 `run`；或使用 1.21.1 现有机制替代 |
| `Only one entity is allowed...` | 指令要求单目标，但选择器可能匹配多个实体 | 在目标选择器中添加 `limit=1`（如 `@e[...,limit=1,sort=nearest]`） |
| `Cannot modify player data` | 试图对玩家使用 `/data merge entity` 或 `/data modify` | 改用 `/effect`、`/attribute`、`/item replace`、`/gamemode` 等玩家允许指令 |
| 怪物/箱子未出现预期装备或物品 | 实体/容器 NBT 中物品仍写旧版 `Count:1b` 大写键 | 改为 `{id:"...",count:1}` 小写组件化格式 |
| 命令执行无任何输出且未生效 | 选择器范围条件不满足（如误写了 `<10` 或超出了距离） | 检查选择器语法，将范围改用 `distance=..10` 双点记号 |
