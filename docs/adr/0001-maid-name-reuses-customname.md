# 女仆名字复用命名牌，不建独立字段

用户最初提议为女仆建独立名字字段并绑定 UUID。核实主模组现实：`Entity.getName()` 有命名牌时返回命名牌、无则返回皮肤名；`ChatClientInfo.fromMaid` 的 name 取同一值；人设模板变量只有 `main_setting/owner_name/chat_language/tts_language/available_skills`，名字不进人设；Jade 与头顶显示均直接取命名牌。决定彻底复用命名牌：rename 即 `setCustomName`，UUID 仅作 `MaidFinder` 查找键，不做 NBT / Capability / sidecar 存储。
