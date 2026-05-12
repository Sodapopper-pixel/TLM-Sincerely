# TLM Sincerely - 开发计划

## 项目信息

- **模组名称**：《车万女仆：真心为你》
- **Mod ID**: `tlm_sincerely`
- **版本**: 1.0.0 (Forge 1.20.1)
- **主模组依赖**: touhou_little_maid ≥ 1.5.1
- **开发者**: terk
- **最近更新日期**：

---

## 本文档更新规范

1. **更新条件**：只有在用户明确说明下才能更新本计划
2. **内容原则**：精简内容，以已实现和待实现为主，减少代码内容写入
3. **任务大纲**：大功能 → 小功能 → 细则

---

## 项目结构

```
src/main/java/com/github/tartaricacid/tlm_sincerely/
├── SincerelyExtension.java      # 入口类，实现 ILittleMaid
├── chatbar/
│   ├── MaidFinder.java          # 女仆查找逻辑
│   ├── ChatParser.java          # 消息解析
│   └── ChatTarget.java          # 解析结果
├── command/
│   └── ChatCommand.java         # Brigadier 命令
src/main/resources/
├── META-INF/mods.toml           # 模组元数据
├── assets/tlm_sincerely/lang/   # 国际化文件
├── pack.mcmeta                  # 资源包元数据
```

---

## 构建命令

**环境要求**：Java 17（系统默认 Java 25 会报错）

```powershell
# 设置 Java 17 环境
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"

# 构建
.\gradlew.bat build --no-daemon

# 输出位置
build/libs/tlm_sincerely-1.20.1-forge-1.0.0.jar
```

---

## 测试命令

```powershell
# 复制Mixin模组到run/mods目录（解决开发环境refMap问题）
.\gradlew.bat copyModsToLocalRun --no-daemon

# 启动测试客户端（首次较慢，需下载资源）
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
.\gradlew.bat runClient --no-daemon
```

---

### 女仆 API

主模组提供的关键 API：
- `EntityMaid` - 女仆实体类
- `MaidAIChatManager.chat()` - AI 对话接口
- `ChatClientInfo` - 聊天客户端信息
- `ILittleMaid` - 附属扩展接口

---

## API 参考

**主模组开发文档**：`wiki-reference/docs/wiki/dev/`

关键文件：
- `如何开始.md` - 入口注册方式
- `ai/overview.md` - AI 系统概述
- `ai/context.md` - 上下文注册

**主模组源码**：`D:\Minecraft\TouhouLittleMaid-1.20`

---

## 已实现与待实现功能

### 一、聊天栏女仆对话（基本实现）

- `CHATBAR.md` - 聊天栏女仆对话功能开发文档（架构、数据流、Mixin 细节）

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

#### 8. 聊天栏 GUI 按钮（待实现）
- ⏸ Mixin ChatScreen 注入按钮
- ⏸ 备选方案：按键绑定 / Overlay HUD

### 二、多工作模式（已实现）

- `PRIORITY.md` - 多工作模式开发文档（架构、API、Mixin、配置格式）

#### 1. 数据层
- ✓ TaskPriorityPreset - 预设数据类（优先级映射 + 排序列表）
- ✓ TaskPriorityManager - JSON 持久化管理（CRUD、序列化/反序列化）
- ✓ 配置文件：`config/tlm_sincerely/task_priority_presets.json`

#### 2. 自动切换
- ✓ TaskAutoSwitchHandler - ServerTickEvent 轮询，每 20 tick 检查
- ✓ 按优先级 + IMaidTask.isEnable() 条件自动切换
- ✓ 冷却机制（默认 100 tick = 5 秒）

#### 3. GUI 层
- ✓ TaskPriorityScreen - 独立 Screen（非 Container）
- ✓ 侧边栏入口（MaidSideTabsMixin 注入 index=2）
- ✓ 双列布局：左侧未排序，右侧已排序
- ✓ 顶部两行控件：[总开关] [预设名] [<] [>] / [新建] [删除]
- ✓ 滚轮修改优先级数字（1-10 循环）
- ✓ ▲▼ 按钮调整同优先级内顺序
- ✓ 右键移除任务
- ✓ 实时保存，无需手动保存
- ✓ 自定义侧边栏按钮（PrioritySideTabButton + 独立纹理）

#### 4. AI Tool
- ✓ TaskPriorityTool - 实现 ITool<Result>
- ✓ 支持 query / set / switch_preset 三个 action

#### 5. 配置系统
- ✓ PriorityConfig - ENABLED + COOLDOWN（配置段：`[multi_task]`）
- ✓ 默认关闭多工作模式
- ✓ 界面总开关实时切换

#### 6. 国际化
- ✓ zh_cn / en_us 完整语言文件

#### 7. 已知问题
- ⚠ 滚轮修改优先级时，焦点与光标所在行可能不同步（GUI 缩放导致坐标偏差）

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

