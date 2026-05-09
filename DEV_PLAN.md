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

### 一、聊天栏女仆对话

#### 1. 基础对话命令
- ✓ `/tlmchat <消息>` - 与最近女仆对话
- ✓ `/tlmchat to <名字> <消息>` - 与指定名字女仆对话
- ✓ `/tlmchat uuid <UUID> <消息>` - 与指定 UUID 女仆对话
- ✓ `/tlmchat list` - 显示附近女仆列表

#### 2. 模式切换命令
- ✓ `/tlmchat mode` - 切换女仆对话模式
- ✓ `/tlmchat mode on/off` - 开启/关闭女仆对话模式
- ✓ `/tlmchat global` - 切换全局/私聊模式

#### 3. 女仆查找逻辑
- ✓ MaidFinder - 女仆查找算法（模糊匹配、距离优先）
- ✓ 同名女仆提醒（黄色提示 + UUID 截断）
- ✓ Tab 补全（女仆名字、UUID）

#### 4. 配置系统
- ✓ ForgeConfigSpec 配置文件
- ✓ Configured 模组 GUI 支持
- ✓ 配置项：对话模式、全局可见、前缀要求、对话范围

#### 5. 国际化
- ✓ zh_cn / en_us 语言文件

---

## 待实现功能 ⏸

### 一、聊天栏女仆对话（完善）

#### 6. 聊天栏 GUI 按钮
- ⏸ Mixin ChatScreen 注入按钮
- ⏸ 按钮位置：输入框上方右侧
- ⏸ 焦点恢复问题待解决
- ⏸ 备选方案：按键绑定 / Overlay HUD

#### 7. 聊天事件监听
- ⏸ ServerChatEvent 监听
- ⏸ 前缀解析（`@名字` 格式）
- ⏸ 无前缀自动对话

---

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
- `ButtonEnabled` - 聊天栏按钮开关（暂未生效）
- `ChatModeEnabled` - 女仆对话模式开关
- `GlobalChatVisible` - 全局聊天可见性
- `RequirePrefix` - 是否需要前缀
- `AutoChatRange` - 自动对话范围
- `PrefixPattern` - 前缀字符