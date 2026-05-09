# 聊天栏女仆对话 - 功能开发文档

## 架构概览

```
┌─────────────────────────────────────────────────────────────────┐
│                        玩家输入消息                               │
└──────────────┬──────────────────────────┬───────────────────────┘
               │                          │
         聊天栏输入                    /tlmchat 命令
               │                          │
      ChatBarHandler              ChatCommand.register()
      (ServerChatEvent)           (Brigadier Dispatcher)
               │                          │
               └──────────┬───────────────┘
                          │
                    MaidFinder
                    (女仆查找)
                          │
                  maid.getAiChatManager().chat()
                          │
                  主模组 LLM 处理
                          │
          ChatBubbleManager.addLLMChatText()
                          │
              MaidChatBroadcastMixin  ← 拦截 sendSystemMessage
                          │
            GLOBAL_VISIBLE? ──true──→ broadcastSystemMessage (全局)
                          │
                         false
                          │
                    sendSystemMessage (仅主人)
```

## 文件清单

| 文件 | 职责 | 平台 |
|------|------|------|
| `chatbar/ChatBarHandler.java` | ServerChatEvent 监听，聊天栏消息拦截与转发 | 双端 |
| `chatbar/MaidFinder.java` | 女仆查找算法（名字/NUID/距离） | 双端 |
| `command/ChatCommand.java` | Brigadier 命令注册 + Tab 补全 | 服务端 |
| `config/subconfig/ChatBarConfig.java` | 聊天栏配置项定义 | 双端 |
| `config/GeneralConfig.java` | 配置聚合器 | 双端 |
| `client/gui/ConfigScreen.java` | Cloth Config GUI | 客户端 |
| `mixin/MaidChatBroadcastMixin.java` | Mixin 拦截女仆回复 → 全局广播 | 双端 |
| `SincerelyExtension.java` | 入口类，注册配置和事件 | 双端 |

## 配置项

| 键名 (toml) | 类型 | 默认值 | 说明 |
|-------------|------|--------|------|
| `ChatModeEnabled` | bool | false | 女仆对话模式总开关 |
| `GlobalChatVisible` | bool | true | 全局/私聊可见性 |
| `RequirePrefix` | bool | false | 严格前缀模式 |
| `AutoChatRange` | double | 5.0 | 自动对话范围(0=禁用) |
| `PrefixPattern` | string | "@" | 前缀字符 |

切换方式：
- Cloth Config GUI（模组列表 → TLM Sincerely）
- `/tlmchat mode` / `mode on/off` / `global` 命令
- 直接编辑 `config/tlm_sincerely-common.toml`

## 数据流详解

### 聊天栏对话流程 (ChatBarHandler)

```
ServerChatEvent 触发
  │
  ├─ CHAT_MODE 未开启 → 跳过
  │
  ├─ 解析 @前缀（始终执行）
  │   ├─ 匹配到 → 查找女仆（MaidFinder.findByName）
  │   │   ├─ 找到 → 发送给女仆
  │   │   └─ 未找到 → 错误提示（红色）
  │   └─ 未匹配到 → 继续下一步
  │
  ├─ REQUIRE_PREFIX=true（严格前缀）
  │   └─ 不匹配任何女仆 → 消息正常广播(无处理)
  │
  ├─ REQUIRE_PREFIX=false
  │   └─ AUTO_CHAT_RANGE > 0 → 自动查找范围内最近女仆
  │
  └─ 找到目标女仆 → 发送
      ├─ sendChatToMaid(maid, player, message)
      └─ GLOBAL_VISIBLE? 
          ├─ true  → 玩家消息广播(event 不 cancel)
          └─ false → 玩家消息隐藏(canceled) + 私聊回声
```

### 女仆回复流程 (MaidChatBroadcastMixin)

```
主模组 LLM 处理完毕
  │
  └─ ChatBubbleManager.addLLMChatText(message, waitingBubbleId)
       │
       ├─ 添加聊天气泡（entityData 同步，所有玩家可见）
       │
       ├─ if (maid.owner instanceof ServerPlayer player):
       │     player.sendSystemMessage(msg)  ← @Redirect 拦截点
       │
       └─ MaidChatBroadcastMixin.redirectMaidReply(player, message)
            ├─ GLOBAL_VISIBLE=true  → broadcastSystemMessage(全局广播)
            └─ GLOBAL_VISIBLE=false → sendSystemMessage(仅主人)
```

## Mixin 技术细节

### 目标类
`com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleManager`

### 注入方法
`addLLMChatText(String message, long waitingChatBubbleId)`

### 注入点
`ServerPlayer.sendSystemMessage(Component)` 调用处（`@Redirect` + `remap=false`）

### 关键陷阱

1. **`compatibilityLevel` 限制**：Mixin 0.8.5 最大支持 `JAVA_13`，不能写 `JAVA_17`
2. **`remap = false` 必需**：`sendSystemMessage(Component)` 是 Forge 打补丁添加的方法，无原版 SRG 映射。不加 `remap=false` 会错误映射到 `m_213846_`（参数数量不匹配），导致注入永不命中
3. **`@Inject` 与 `@Redirect` 冲突**：两个注解同时注入同一方法会导致 Mixin 被跳过（"Preparing (1)" 但无 "Mixing" 消息）
4. **`addLLMChatText` 无 SRG 映射**：主模组自定义方法，`@Inject` 需要 `remap=false`

### 验证方法
```powershell
# 运行时搜索日志
Select-String -Path run\logs\latest.log -Pattern "MaidChatBroadcast"

# 预期输出（Mixin 已应用）：
# Mixing MaidChatBroadcastMixin from tlm_sincerely.mixins.json into ChatBubbleManager

# 预期输出（Mixin 未应用）：
# Preparing tlm_sincerely.mixins.json (1)   ← 但无后续 "Mixing" 行
```

## 已知问题

| 问题 | 状态 | 说明 |
|------|------|------|
| 聊天栏 Tab 补全 | ⚠ 已知限制 | Brigadier 补全仅在命令环境生效，普通聊天栏不触发。需 Mixin ChatScreen 实现 |
| 女仆回复广播验证 | ⚠ 需多人测试 | 单人游戏中 broadcast 与 private 效果相同，需逻辑服务器+第二个客户端验证 |
| 女仆消息灰色格式 | ℹ 主模组行为 | 主模组 `addLLMChatText` 使用 `sendSystemMessage`（灰色），本 mod 未修改格式 |
| GUI 按钮焦点问题 | ⚠ 暂停 | ChatScreen Mixin 按钮方案存在焦点丢失问题，后续考虑按键绑定替代 |

## 维护与扩展

### 添加新配置项
1. 在 `ChatBarConfig.init()` 中添加 `builder.define()`
2. 在 `ConfigScreen.create()` 中添加对应控件
3. 在 `lang/zh_cn.json` / `en_us.json` 中添加 i18n 键
4. 重新构建后删除旧配置文件让 Forge 自动重建

### 添加新 Mixin
1. 在 `mixin/` 包下创建类，使用 `@Mixin` 注解
2. 在 `tlm_sincerely.mixins.json` 的 `mixins` 或 `client` 数组中注册
3. 注意 `compatibilityLevel` 和 `remap` 设置
