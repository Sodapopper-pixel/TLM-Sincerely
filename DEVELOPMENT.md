# 开发手册与注意事项

## 项目结构

```
src/main/java/com/github/tartaricacid/tlm_sincerely/
├── SincerelyExtension.java      # 入口类，实现 ILittleMaid
├── ai/                          # AI Context 与 Tool
├── chatbar/
│   ├── ChatBarHandler.java       # 聊天栏事件与前缀解析
│   └── MaidFinder.java           # 女仆查找逻辑
├── command/
│   ├── ChatCommand.java          # 对话命令
│   ├── MemoryCommand.java        # 记忆管理命令
│   └── UnicodeWordArgument.java  # 中文参数支持
├── memory/                       # 女仆记忆持久化
└── priority/                     # 多工作检测、缓存与切换决策
src/main/resources/
├── META-INF/mods.toml           # 模组元数据
├── assets/tlm_sincerely/lang/   # 国际化文件
├── pack.mcmeta                  # 资源包元数据
└── data/touhou_little_maid/      # 主模组 Skill 数据
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
build/libs/tlm_sincerely-1.20.1-forge-0.1.0.jar
```

---

## 测试命令

```powershell
# 启动测试客户端（首次较慢，需下载资源）
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
.\gradlew.bat runClient --no-daemon

# 或双击项目根目录 runClient.bat
```

`copyModsToLocalRun` 仅为历史兼容空任务，不再复制任何模组。开发依赖统一通过 `runtimeOnly fg.deobf(...)` 加载。

---

### 女仆 API

主模组提供的关键 API：
- `EntityMaid` - 女仆实体类
- `MaidAIChatManager.chat()` - AI 对话接口
- `ChatClientInfo` - 聊天客户端信息
- `ILittleMaid` - 附属扩展接口

---

## API 参考（模组作者本机环境）

**主模组开发文档**：`wiki-reference/docs/wiki/dev/`

关键文件：
- `如何开始.md` - 入口注册方式
- `ai/overview.md` - AI 系统概述
- `ai/context.md` - 上下文注册

**主模组源码**（远端，按需 `git clone`）：
- 官方仓库：`https://github.com/TartaricAcid/TouhouLittleMaid`（1.20.1 对应 `1.20` 分支）
- 本机不保留主模组源码副本，需要时从官方仓库拉取（对应版本为 `1.5.2-forge`）

---

## 操作红线

### 1. 禁止删除整个 `run/` 目录
清理测试环境时**只删目标文件**，不要删除整个 `run/` 目录：
- ✓ 可删：`run/config/tlm_sincerely*`、`run/crash-reports/*`
- ✗ 不可删：`run/` 整体、`run/config/`、`run/mods/`、`run/logs/`

删除 `run/` 会丢失第三方 mod 的缓存配置（Embeddium、Configured 等），导致启动异常。

### 2. 修改 build.gradle 时区分"专属配置"和"共享基础设施"
以下两项是 **Mixin 框架全局 JVM 参数**，与是否使用自己的 Mixin 无关，不能删除：
```groovy
property 'mixin.env.remapRefMap', 'true'
property 'mixin.env.refMapRemappingFile', "${projectDir}/build/createSrgToMcp/output.srg"
```
所有使用 Mixin 的第三方 mod（Embeddium、Create、Sophisticated Backpacks 等）都依赖它们做方法名重映射。

### 3. 修改 build.gradle 后用 `git diff` 核对改动
每次修改完 `build.gradle`，检查 diff 确保没有误删共享依赖或配置项。

## 常见陷阱

### 配置冲突 `Config conflict detected!`
- **原因**：`@Mod` 和 `@LittleMaidExtension` 注解共存导致双重实例化
- **解决**：使用静态布尔值 `configRegistered` 防止重复注册

### Embeddium 兼容性
- `mixin.env.remapRefMap` 缺失时，Embeddium 的 `DrawContextMixin` 会抛出 `InvalidInjectionException`
- 该错误堆栈指向 Embeddium，但根因是 build.gradle 缺少 Mixin 全局 JVM 参数

### 自定义 ArgumentType 必须注册序列化器
- **现象**：自定义 `ArgumentType` 编译通过但进存档报 `"无效的玩家数据"`
- **日志关键行**：`Unrecognized argument type ... at ArgumentTypeInfos.byClass()`
- **根因**：Minecraft 在玩家登录时将命令树序列化发送给客户端，`ArgumentTypeInfos` 找不到对应类的序列化器 → 抛异常 → `ServerLoginPacketListenerImpl` 终止登录
- **解决**：在 Mod 构造器中早于命令注册时调用：
  ```java
  ArgumentTypeInfos.registerByClass(
      MyArgType.class,
      SingletonArgumentInfo.contextFree(() -> new MyArgType())
  );
  ```
- **注意**：`SingletonArgumentInfo.contextFree(Supplier)` 比 `new SingletonArgumentInfo<>(Supplier)` 类型推断更稳定

### Brigadier 1.1.8 不支持 Unicode 参数（MC 1.20.1）
- **现象**：命令中传入中文/非 ASCII 参数时报 `"参数后应有空格分隔，但发现了紧邻的数据"`
- **根因**：Brigadier 1.1.8 的 `StringReader.isAllowedInUnquotedString()` 只允许 `0-9A-Za-z_-.+`。`StringArgumentType.string()` 内部调用 `readUnquotedString()`，遇中文字符立即停止读取，参数值为空串
- **验证**：Minecraft bug [MC-260354](https://bugs.mojang.com/browse/MC-260354)，1.20.2 才修复
- **解决**：自建参数类型，逐字符读取直到空格（不依赖 `isAllowedInUnquotedString`）：
  ```java
  public class UnicodeWordArgument implements ArgumentType<String> {
      @Override
      public String parse(StringReader reader) {
          final int start = reader.getCursor();
          while (reader.canRead() && reader.peek() != ' ') {
              reader.skip();
          }
          return reader.getString().substring(start, reader.getCursor());
      }
  }
  ```
- **注意**：自定义类型同样需要 `ArgumentTypeInfos.registerByClass()` 注册序列化器

### Brigadier 命令树：greedyString 与 literal 同层歧义
- **现象**：命令树中 `greedyString` 和 `literal` 作为同一父节点的子节点时，部分路径无法命中或报 `"参数后应有空格分隔"`
- **根因**：`greedyString` 吞掉全部剩余输入，同一层的 `literal` 子节点永远无法被匹配到。Brigadier 的多路径解析会产生歧义
- **解决**：将 `core`/`archive` 从子字面量提升为独立子命令（如 `set` → archive，`set-core` → core），确保每个分支末端只有 `greedyString` 单一终端
- **反面模式**：
  ```java
  // ❌ 错误：greedyString 与 literal 同层
  .then(argument("value", greedyString())
      .executes(defaultAction)
      .then(literal("core").executes(coreAction)))
  
  // ✅ 正确：literal 在单独分支
  .then(argument("value", greedyString()).executes(defaultAction))
  .then(literal("core")
      .then(argument("value", greedyString()).executes(coreAction)))
  ```

## Mixin 开发笔记

### compatibilityLevel 必须匹配 Mixin 版本
Mixin 0.8.5 最大支持 `JAVA_13`，不能写 `JAVA_17`。写错会导致 Mixin 类不加载。
```json
{ "compatibilityLevel": "JAVA_8" }
```

### 自定义方法需要 `remap = false`
- 主模组/第三方 mod 的自定义方法（非原版 Minecraft）**没有 SRG 映射**
- `@Inject(method = "...", remap = false)` — 不加会编译失败：`Unable to locate obfuscation mapping`
- `@Accessor` 也不需要特殊处理（自定义字段名不会被 remap）

### @Redirect target 与 Forge 方法
`ServerPlayer.sendSystemMessage(Component)` 是 **Forge 打补丁添加的便捷方法**，不是原版方法。
- 原版方法是 `displayClientMessage(Component, boolean)`（SRG: `m_213846_`）
- 如果 `@Redirect` 不加 `remap = false`，refmap 会错误映射到原版方法（参数数量不匹配），导致注入永远不命中
- **解决方案**：对 Forge 添加的方法使用 `@Redirect(remap = false)`

### mixingradle refmap 路径
- 生成位置：`build/tmp/compileJava/compileJava-refmap.json`
- `add sourceSets.main` 会自动复制到 jar 和 classpath
- dev 环境 WARN "could not read refmap" 可忽略（Parchment 环境不需要）
- jar 出现 refmap 重复时加 `duplicatesStrategy = DuplicatesStrategy.EXCLUDE`

### 编译失败排除
- `error: package org.spongepowered.asm.mixin.injection.wrap does not exist` → `@WrapOperation` 来自 MixinExtras，不包含在 `mixingradle 0.7 + Mixin 0.8.5` 中。需要额外添加 MixinExtras 依赖或改用 `@Redirect`
- `Class version 61 required is higher than the class version supported (JAVA_8 supports class version 52)` → 无影响的 WARN，仅表示 Mixin 运行时 class version 高于声明的 compatibilityLevel

## 测试流程

```
# 1. 构建
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
.\gradlew.bat build --no-daemon

# 2. 启动测试客户端
.\gradlew.bat runClient --no-daemon

# 3. 验证配置文件
cat run\config\tlm_sincerely-common.toml

# 4. 查看崩溃日志
ls run\crash-reports\
cat run\logs\latest.log

# 5. 清理特定配置（如需）
Remove-Item run\config\tlm_sincerely* -Force
```
