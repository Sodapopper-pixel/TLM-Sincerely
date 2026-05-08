# AGENTS.md

## 语言政策

- 默认使用简体中文回答
- 代码、命令、报错、API 名称保持原文，不要强行翻译
- 提问澄清时也使用中文

---

## 项目信息

- **模组名称**：《车万女仆：真心为你》
- **Mod ID**: `tlm_sincerely`
- **平台**: Forge 1.20.1
- **Java**: 17
- **主模组依赖**: touhou_little_maid ≥ 1.5.1
- **开发者**: terk

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

# 启动测试服务器
.\gradlew.bat runServer --no-daemon
```

---

## 游戏内测试

**命令格式**：
| 命令 | 效果 |
|------|------|
| `/tlmchat 你好吗` | 与最近女仆对话 |
| `/tlmchat to 琪琪 你好吗` | 与指定名字的女仆对话（同名取最近） |
| `/tlmchat uuid <UUID> 你好吗` | 与指定 UUID 的女仆对话（精确匹配） |
| `/tlmchat list` | 显示附近女仆列表（名字、UUID、距离） |

**同名女仆提醒**：
- 当匹配到多个同名女仆时，会自动选择最近的
- 系统会发送黄色提示消息，包含 UUID（8位截断）
- 可使用 `/tlmchat list` 查看详情，或 `/tlmchat uuid` 精确指定

**测试步骤**：
1. 启动 runClient
2. 创建/进入测试世界
3. 放置女仆并驯服
4. 配置 AI 聊天（需要有效的 LLM API）
5. 在聊天栏输入 `/tlmchat` 命令测试

---

## 开发规范

**重要约束**：
- **不自启动 runClient**：每次修改完成后，需要用户明确要求才能执行 `runClient`
- **不自 git 提交**：需要用户明确要求才能执行 `git commit`
- **不自运行测试**：构建成功即视为完成，测试由用户决定

### 入口类

使用 `@LittleMaidExtension` 注解 + `ILittleMaid` 接口：

```java
@Mod("tlm_sincerely")
@LittleMaidExtension
public class SincerelyExtension implements ILittleMaid {
    public SincerelyExtension() {
        MinecraftForge.EVENT_BUS.register(this);
    }
}
```

### 事件注册

通过 `MinecraftForge.EVENT_BUS` 注册事件处理器：

```java
@SubscribeEvent
public void onRegisterCommands(RegisterCommandsEvent event) {
    ChatCommand.register(event.getDispatcher());
}
```

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

## 依赖配置

主模组通过 Modrinth Maven 自动下载：

```groovy
repositories {
    maven {
        url = "https://api.modrinth.com/maven"
        content {
            includeGroup "maven.modrinth"
        }
    }
}

dependencies {
    implementation fg.deobf("maven.modrinth:touhou-little-maid:1.5.2-forge+mc1.20.1")
}
```

---

## 常见问题

| 问题 | 解决方案 |
|------|----------|
| Gradle 报错 "Unsupported class file major version 69" | 设置 `JAVA_HOME` 为 JDK 17 |
| mods.toml 乱码 | 直接使用英文值，避免 Gradle 变量替换 |
| runClient 启动慢 | 正常现象，首次需下载资源；后续会更快 |
| 找不到女仆 | 确保女仆已驯服且在 64 格范围内 |