# TLM Sincerely - 开发计划

## 项目信息

- **模组名称**：《车万女仆：真心为你》
- **Mod ID**: `tlm_sincerely`
- **版本**: 0.1.0 (Forge 1.20.1)
- **主模组依赖**: touhou_little_maid ≥ 1.5.1
- **开发者**: terk

---

## 功能需求

### 核心功能：聊天栏对话

**现状**：
- 主模组支持 AI 聊天，但需通过 GUI（AIChatScreen）输入
- AI 回复已会发送到聊天栏（格式：`<女仆名> 回复内容`）

**目标**：
- 直接在聊天栏输入消息与女仆对话
- 支持多女仆区分

---

## 实现方案

### 女仆区分机制（混合方案）

| 消息格式 | 目标女仆 |
|----------|----------|
| `@名字 消息内容` | 匹配指定名字的女仆（模糊匹配） |
| `消息内容`（无前缀） | 对话最近/跟随的女仆 |

**女仆查找逻辑**：
1. **有前缀 `@名字`**：
   - 提取名字部分
   - 在玩家拥有的女仆中匹配（忽略大小写、模糊匹配）
   - 找到多个匹配时选择距离最近的
   
2. **无前缀**：
   - 查找玩家拥有的最近女仆
   - 优先选择正在跟随的女仆
   - 如果无女仆在附近，提示玩家

---

## 技术实现

### 1. 监听玩家聊天事件

```java
@SubscribeEvent
public void onServerChat(ServerChatEvent event) {
    ServerPlayer player = event.getPlayer();
    String rawMessage = event.getRawText();
    
    // 解析消息，确定目标女仆
    ChatTarget target = parseChatTarget(player, rawMessage);
    
    if (target.hasMaid()) {
        // 发送给女仆 AI 聊天
        target.maid().getAiChatManager().chat(target.message(), clientInfo, player);
        // 取消原始消息广播（避免重复显示）
        event.setCanceled(true);
    }
}
```

### 2. 女仆查找算法

```java
public class MaidFinder {
    /**
     * 查找玩家拥有的所有女仆
     */
    public static List<EntityMaid> getOwnedMaids(ServerPlayer player) {
        return player.level.getEntitiesOfClass(EntityMaid.class, 
            player.getBoundingBox().inflate(64),
            maid -> maid.isOwnedBy(player) && maid.isAlive()
        );
    }
    
    /**
     * 根据名字匹配女仆（模糊匹配）
     */
    public static EntityMaid findByName(ServerPlayer player, String name) {
        List<EntityMaid> maids = getOwnedMaids(player);
        String lowerName = name.toLowerCase();
        
        // 精确匹配优先
        for (EntityMaid maid : maids) {
            String maidName = maid.getName().getString().toLowerCase();
            if (maidName.equals(lowerName)) {
                return maid;
            }
        }
        
        // 模糊匹配（包含）
        List<EntityMaid> fuzzyMatches = maids.stream()
            .filter(m -> m.getName().getString().toLowerCase().contains(lowerName))
            .toList();
        
        if (fuzzyMatches.size() == 1) {
            return fuzzyMatches.get(0);
        }
        if (fuzzyMatches.size() > 1) {
            // 多个匹配，选择最近的
            return findNearest(player, fuzzyMatches);
        }
        
        return null;
    }
    
    /**
     * 查找最近的女仆（优先跟随状态）
     */
    public static EntityMaid findNearest(ServerPlayer player, List<EntityMaid> maids) {
        return maids.stream()
            .min((m1, m2) -> {
                // 优先跟随状态
                if (m1.isFollowing() != m2.isFollowing()) {
                    return m1.isFollowing() ? -1 : 1;
                }
                // 其次距离
                double d1 = m1.distanceToSqr(player);
                double d2 = m2.distanceToSqr(player);
                return Double.compare(d1, d2);
            })
            .orElse(null);
    }
}
```

### 3. 消息解析

```java
public class ChatParser {
    private static final Pattern AT_PATTERN = Pattern.compile("^@(.+?)\\s+(.+)$");
    
    public static ChatTarget parse(ServerPlayer player, String message) {
        Matcher matcher = AT_PATTERN.matcher(message);
        
        if (matcher.find()) {
            String name = matcher.group(1);
            String content = matcher.group(2);
            EntityMaid maid = MaidFinder.findByName(player, name);
            return new ChatTarget(maid, content, true);
        }
        
        // 无前缀，查找最近女仆
        EntityMaid maid = MaidFinder.findNearest(player, MaidFinder.getOwnedMaids(player));
        return new ChatTarget(maid, message, false);
    }
}

public record ChatTarget(@Nullable EntityMaid maid, String message, boolean hasPrefix) {
    public boolean hasMaid() {
        return maid != null;
    }
}
```

### 4. ChatClientInfo 获取

需要从女仆获取聊天配置信息：
```java
ChatClientInfo clientInfo = ChatClientInfo.fromMaid(maid);
```

---

## 配置选项

```java
// 是否启用聊天栏对话
public static final ForgeConfigSpec.BooleanValue CHAT_BAR_CHAT_ENABLED;

// 无前缀时的默认对话距离（格）
public static final ForgeConfigSpec.IntValue DEFAULT_CHAT_RANGE;

// 是否取消原始消息广播（避免重复显示）
public static final ForgeConfigSpec.BooleanValue CANCEL_ORIGINAL_MESSAGE;
```

---

## 开发步骤

### Phase 1: 基础功能 ✓
- 项目结构搭建
- 入口类创建

### Phase 2: 聊天栏对话 ✓
1. 创建 `MaidFinder` 类（女仆查找逻辑）
2. 创建 `ChatParser` 类（消息解析）
3. 创建 `ChatBarHandler` 类（事件监听）
4. 注册 Forge 事件总线
5. 构建成功

### Phase 3: 配置与优化（可选）
1. 添加配置文件
2. 处理边界情况（无女仆、多个匹配等）
3. 添加提示消息（找不到女仆时提示）
4. 国际化支持

---

## 待确认事项

- [x] 女仆区分方案：混合方案
- [ ] 配置项是否需要？
- [ ] 消息取消策略（是否显示原始消息）