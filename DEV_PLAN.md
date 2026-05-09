# TLM Sincerely - 开发计划

## 项目信息

- **模组名称**：《车万女仆：真心为你》
- **Mod ID**: `tlm_sincerely`
- **版本**: 1.0.0 (Forge 1.20.1)
- **主模组依赖**: touhou_little_maid ≥ 1.5.1
- **开发者**: terk

---

## 本文档更新规范

1. **更新条件**：只有在用户明确说明下才能更新本计划
2. **内容原则**：精简内容，以已实现和待实现为主，减少代码内容写入
3. **任务大纲**：大功能 → 小功能 → 细则

---

## 已实现功能 ✓

### 一、聊天栏女仆对话（基本实现）

#### 1. 基础对话命令
- ✓ `/tlmchat <消息>` - 与最近女仆对话
- ✓ `/tlmchat to <名字> <消息>` - 与指定名字女仆对话
- ✓ `/tlmchat uuid <UUID> <消息>` - 与指定 UUID 女仆对话
- ✓ `/tlmchat list` - 显示附近女仆列表
- ✓ Tab 补全（女仆名字、UUID，仅命令环境）

#### 2. 模式切换命令
- ✓ `/tlmchat mode` - 切换女仆对话模式
- ✓ `/tlmchat mode on/off` - 开启/关闭女仆对话模式
- ✓ `/tlmchat global` - 切换全局/私聊模式

#### 3. 聊天栏对话（ServerChatEvent）
- ✓ 聊天栏输入自动拦截（需开启女仆对话模式）
- ✓ `@名字` 前缀始终解析（不依赖严格前缀模式开关）
- ✓ 严格前缀模式：开启后仅 @名字 消息发给女仆
- ✓ 自动对话范围：无前缀时自动匹配最近女仆
- ✓ 全局/私聊控制：玩家消息广播/隐藏
- ✓ 女仆回复全局广播（Mixin `ChatBubbleManager.addLLMChatText`）

#### 4. 女仆查找逻辑
- ✓ MaidFinder - 女仆查找算法（模糊匹配、距离优先）
- ✓ 同名女仆提醒（黄色提示 + UUID 截断）

#### 5. 配置系统
- ✓ 主模组风格 subconfig 分类（ChatBarConfig → GeneralConfig）
- ✓ Cloth Config GUI（ConfigScreen）
- ✓ 配置项：女仆对话模式、全局聊天可见、严格前缀模式、自动对话范围、前缀字符

#### 6. 国际化
- ✓ zh_cn / en_us 完整语言文件

#### 7. 已知限制
- ⚠ 聊天栏 Tab 补全仅命令环境生效（需 Mixin ChatScreen）
- ⚠ 女仆回复广播需多玩家环境验证（单人无感知差异）
- ⚠ 聊天栏 GUI 按钮暂未实现（方案待定）

---

## 待实现功能 ⏸

### 一、聊天栏女仆对话（待完善）

#### 聊天栏 GUI 按钮
- ⏸ Mixin ChatScreen 注入按钮
- ⏸ 备选方案：按键绑定 / Overlay HUD

### 二、工作优先级排序

- 待规划

---

### 三、简易记忆系统

- 待规划

---

### 四、女仆间交流系统

- 待规划

---

### 五、预设人格

- 待规划

---

### 六、性格系统

- 待规划

---

### 七、女仆成长系统

- 待规划

---

## 技术备注

### 配置文件位置
- 客户端：`config/tlm_sincerely-common.toml`
- 使用 Configured 模组 GUI 编辑

### 命令参考
```
/tlmchat mode          # 切换女仆对话模式
/tlmchat mode on       # 开启
/tlmchat mode off      # 关闭
/tlmchat global        # 切换全局/私聊
/tlmchat <消息>        # 与最近女仆对话
/tlmchat to <名字> <消息>
/tlmchat uuid <UUID> <消息>
/tlmchat list          # 显示女仆列表
```

### 配置项参考
- `ChatModeEnabled` - 女仆对话模式开关
- `GlobalChatVisible` - 全局聊天可见性（控制玩家消息和女仆回复）
- `RequirePrefix` - 严格前缀模式
- `AutoChatRange` - 自动对话范围
- `PrefixPattern` - 前缀字符

### 相关文档
- `CHATBAR.md` - 聊天栏女仆对话功能开发文档（架构、数据流、Mixin 细节）