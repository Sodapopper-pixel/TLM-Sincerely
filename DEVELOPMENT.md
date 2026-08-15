# 开发手册与注意事项

## 项目结构

```
src/main/java/com/github/tartaricacid/tlm_sincerely/
├── SincerelyExtension.java      # 入口类，实现 ILittleMaid
├── ai/                          # AI Context 与 Tool
├── chatbar/
│   ├── ChatBarHandler.java       # 聊天栏事件与前缀解析
│   └── MaidFinder.java           # 女仆查找逻辑
├── client/
│   ├── gui/autowork/             # 自动工作 GUI：独立配置 Screen、棕色按钮、底部开关
│   └── network/ClientAutoWorkService.java  # S2C 快照客户端缓存
├── command/
│   ├── ChatCommand.java          # 对话命令
│   ├── MemoryCommand.java        # 记忆管理命令
│   └── UnicodeWordArgument.java  # 中文参数支持
├── memory/                       # 女仆记忆持久化
├── mixin/                        # Mixin（客户端 + 通用）
└── priority/
    ├── autowork/                  # 自动工作模式：状态/预设/网络/菜单/检测/决策
    └── detection/                 # 通用任务检测（农耕/甘蔗/攻击）
src/main/resources/
├── META-INF/mods.toml           # 模组元数据
├── assets/tlm_sincerely/lang/   # 国际化文件
├── pack.mcmeta                  # 资源包元数据
└── tlm_sincerely.mixins.json   # Mixin 配置
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

**主模组源码查询**（本机不保留源码副本，按需查询，从最优到兜底）：

1. **依赖 jar 反编译（首选，最贴近实际编译产物）**：`compileJava` 后主模组 jar 已在 Gradle 缓存：
   `~/.gradle/caches/modules-2/files-2.1/maven.modrinth/touhou-little-maid/1.5.3-forge+mc1.20.1/.../touhou-little-maid-1.5.3-forge+mc1.20.1.jar`
   IDE（IntelliJ 内置反编译器）搜索类名可直接阅读；需要字节码级证据（常量、方法体）时用：
   `javap -p -c -constants -classpath <jar> <全限定类名>`
   这是唯一能精确对应 1.5.3 release 的途径（GitHub `1.20` 分支是快照开发线，可能领先/滞后于正式版）。
2. **浅克隆官方仓库（需要跨文件浏览、git 历史时）**：
   `git clone --depth 1 --branch 1.20 https://github.com/TartaricAcid/TouhouLittleMaid.git`
   对应版本为 `1.5.3-forge`；如需更新 `git -C <目录> pull`。clone 后可用 rg/IDE 全文检索。
3. **GitHub 网页端**（仅看单个文件、不想 clone 时）：`https://github.com/TartaricAcid/TouhouLittleMaid/tree/1.20/src/main/java/...` 或按路径直达文件。
4. **运行目录兜底**：`run/mods/` 中的生产 jar 或开发环境 `run/` 下的 mods，无 Maven 缓存时可用反编译工具打开。

> 注意：GitHub 上该仓库只有 snapshot 预发布 tag，正式版（release）发布在 Modrinth。查询与当前依赖版本严格对应的实现时，一律以第 1 条（依赖 jar）为准；GitHub 源码仅用于理解结构与实现意图。

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

## 开发笔记与常见事项

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
### compatibilityLevel 必须匹配 Mixin 版本
Mixin 0.8.5 最大支持 `JAVA_13`，不能写 `JAVA_17`。写错会导致 Mixin 类不加载。
```json
{ "compatibilityLevel": "JAVA_8" }
```

### 自定义方法需要 `remap = false`
- 主模组/第三方 mod 的自定义方法（非原版 Minecraft）**没有 SRG 映射**
- `@Inject(method = "...", remap = false)` — 不加会编译失败：`Unable to locate obfuscation mapping`
- `@Accessor` 也不需要特殊处理（自定义字段名不会被 remap）

### `@Redirect` target 引用原版方法时的 remap
`ServerPlayer.sendSystemMessage(Component)` 是**原版方法**（SRG: `m_213846_`），不是 Forge 补丁方法。
- **关键**：mixin 到主模组类时 `@Mixin(remap = false)` 会使类内**所有**注入默认不 remap，包括 `@At` target 中的原版方法引用
- 若 target 引用原版方法但 remap=false，refmap 不生成映射条目 -> 生产 jar 中字节码是 SRG 名（`m_213846_`），target 仍是 MCP 名（`sendSystemMessage`）-> **注入不命中 -> 启动崩溃**
- **解决方案**：对 target 引用原版方法的注入，显式设 `@Redirect(remap = true)` 覆盖 `@Mixin(remap = false)`，让 AP 仅为该注入生成 refmap 条目（已验证：mod 类方法名不会被误映射，AP 自动跳过无 SRG 条目的方法）

### mixingradle refmap 路径
- 生成位置：`build/tmp/compileJava/compileJava-refmap.json`
- `add sourceSets.main` 会自动复制到 jar 和 classpath
- dev 环境 WARN "could not read refmap" 可忽略（Parchment 环境不需要）
- jar 出现 refmap 重复时加 `duplicatesStrategy = DuplicatesStrategy.EXCLUDE`

### 编译失败排除
- `error: package org.spongepowered.asm.mixin.injection.wrap does not exist` → `@WrapOperation` 来自 MixinExtras，不包含在 `mixingradle 0.7 + Mixin 0.8.5` 中。需要额外添加 MixinExtras 依赖或改用 `@Redirect`
- `Class version 61 required is higher than the class version supported (JAVA_8 supports class version 52)` → 无影响的 WARN，仅表示 Mixin 运行时 class version 高于声明的 compatibilityLevel

### Mixin 到主模组（TLM）自定义类时的 remap 处理
- **现象**：mixin 到 TLM 的类（如 `MaidAIChatManager`、`ChatBubbleManager`）时，注入的方法名在 refmap 中找不到映射
- **解决**：在 `@Mixin` 注解上设 `remap = false`（使类内所有注入默认不 remap）
- **例外**：若注入的 `@At` target 引用**原版 Minecraft 方法**（如 `ServerPlayer.sendSystemMessage`），需在该注入上显式设 `remap = true`，否则生产 jar 中 target 不命中 -> 启动崩溃（详见上一节）
- **判断规则**：target 引用 mod 类方法 -> remap=false 安全；target 引用 Minecraft 类方法 -> 必须 remap=true

### javap 输出换行截断导致方法签名误读
- **现象**：`javap -p` 输出跨行截断，长参数列表易误读参数个数
- **案例**：`MaidAIChatManager.tts` 实际 4 参数，跨行显示被误读为 5 参数，导致 mixin handler 参数不匹配崩溃
- **解决**：关键签名应单独 javap 确认，不依赖跨行截断的输出

### `@Redirect` target 描述符格式
- 格式：`L<owner>;method_name(descriptor)V`
- 泛型擦除：`List<LLMMessage>` 描述符为 `Ljava/util/List;`

### `@Inject` HEAD cancellable 静默拦截
- void 方法用 `CallbackInfo`，返回值方法用 `CallbackInfoReturnable<T>`
- 用于维护轮静默：拦截气泡、TTS 等输出通道

### 跨 GUI 注入 widget（T-2 修正）
 - TLM 的 `MaidContainerGuiEvent.Init.addButton(String, AbstractWidget)` 是安全的 widget 注入点，无需反射 `addRenderableWidget`
 - **旧方案**曾用 `@Invoker` 或反射注入到 `addTaskListButton`，但会在 `initBaseWidgets` 之前执行，导致 `scheduleButton` 未初始化时 `renderTooltip` 触发 NPE
 - 结论：凡是在女仆 GUI 中追加 widget，一律使用 `MaidContainerGuiEvent.Init` 事件，不要在 `addTaskListButton` 的 HEAD mixin 中操作

### MaidContainerGuiEvent 触发时机（T-2 修正）
 - TLM 在 `initBaseWidgets()` **之后** post `MaidContainerGuiEvent.Init`，此时 `scheduleButton` 等基础 widget 已就绪
 - Init 事件的 `addButton` 会被 TLM 自动 `addRenderableWidget`
 - 如果在 `addTaskListButton` 的 HEAD mixin 里添加 widget，会先于 `scheduleButton` 创建，导致渲染 NPE

### Screen.getFocused / setFocused 实际所在类（补充）
 - `javap -p Screen` 看不到 `getFocused` / `setFocused`，实际定义在父类 `AbstractContainerEventHandler`
 - SRG 名 `m_7222_` / `m_7522_`，编译时正常解析；MCP 映射在 decompile jar 中可能被裁剪
 - 调用方式：`Screen.getFocused()` 返回 `GuiEventListener`，`Screen.setFocused(GuiEventListener)` 设置焦点

### 接口 Mixin 在 Mixin 0.8.5 中不受支持
 - **现象**：`@Mixin(ContainerEventHandler.class)` + `@Inject(method = "charTyped")` 在 compileJava 时报 `Injector in interface is unsupported`
 - **根因**：Mixin 0.8.5 的注解处理器不支持在接口 Mixin 中注入处理器（仅支持类 Mixin）
 - **解决**：改用 Forge `ScreenEvent.CharacterTyped.Pre` 客户端事件，在事件处理器中检查屏幕类型并转发字符输入

### 颜色 ARGB 格式
 - `GuiGraphics.drawString` 的颜色参数是完整的 ARGB 格式
 - `0x3380FF` 是半透明蓝色（alpha=0x33），`0xFF3380FF` 才是不透明蓝色
 - `0xFF5555` 是半透明红色，`0xFFFF5555` 才是不透明红色
 - 结论：GUI 文本颜色必须使用 `0xFF` 开头的完整 ARGB，否则字体透明不可见

### TLM 顶部 Tab 扩展路线
 - 顶部水平 Tab 由 `MaidTabs#getTabs(AbstractMaidContainerGui)` 返回 `MaidTabButton[]` 数组
 - 通过 Mixin `@Inject(method = "getTabs", at = @At("RETURN"), cancellable = true, remap = false)` 追加新 Tab
 - 已用槽位：0:107(MAIN)、1:132(TASK_CONFIG)、2:157(MAID_CONFIG)；第 4 槽用 u=182
 - 按钮构造：`MaidTabButton(x, y, left, String key, OnPress)`，tooltip 自动取 `gui.touhou_little_maid.button.<key>` / `<key>.desc`
 - 独立配置页面应继承 `AbstractMaidContainerGui`，不应继承 `MaidTaskConfigGui`（否则 TLM 会同时高亮两个 Tab）

### 独立菜单 MenuType 注册范式（参照 maid_useful_task）
 - 服务端：`DeferredRegister<MenuType<?>>` + `IForgeMenuType.create((wid, inv, buf) -> new Container(wid, inv, buf.readInt()))`
 - 客户端：`FMLClientSetupEvent.enqueueWork(() -> MenuScreens.register(menuType, Screen::new))`
 - 开屏：`NetworkHooks.openScreen(serverPlayer, MenuProvider, buf -> buf.writeInt(entityId))`
 - 容器：继承 `TaskConfigContainer` 可自动获取物品栏槽位、owner 校验等基础功能

### getXSize() ≠ 任务配置内容区宽度
 - TLM 女仆 GUI 总宽 `imageWidth = 256`（`getXSize()` 返回）
 - 右侧任务配置内容区固定为 `176 × 137`，起始于 `leftPos + 80, topPos + 28`
 - 若用 `getXSize()` 作为覆盖层宽度，会越出女仆 GUI 边界
 - 结论：覆盖层/独立配置页必须硬编码 `176 × 137`

### TLM Tooltip 渲染的 scheduleButton 空指针
 - `AbstractMaidContainerGui.renderTooltip` 无条件访问 `scheduleButton.isHovered()`
 - 若在 GUI 重建过程中有帧渲染到此方法，`scheduleButton` 尚未初始化会触发 NPE
 - 解决：在 `AbstractMaidContainerGuiMixin` 中对 `renderTooltip` HEAD 注入空值保护

### FMLCommonSetupEvent 必须注册到 mod event bus
  - `SincerelyExtension` 构造器中的 `MinecraftForge.EVENT_BUS` 是 Forge 事件总线，不会收到 `FMLCommonSetupEvent`
  - 必须显式 `FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup)`
  - 否则 `AutoWorkNetworking.register()` 永远不执行，channel 为 null

### Compat 报告点击命令对玩家也需开放
  - 默认 `/tlmautowork` 根命令要求权限 2，但兼容报告是 **阅读型** 入口，分类计数点击按钮继承根权限 → 普通玩家点不动
  - **解法**：根命令去掉 `requires` 限制，把 `requires(2)` 移到具体管理子命令（`blacklist`、`whitelist`、`set reminder`、`reload`）
  - 旧聊天中的 `/tlmautowork compat report <page>` 链接仍能直接点击
  - 警示：根命令放空权限后，OP 只能从“查看报告”点出，**改名单仍需 OP**；需要双重检查 Brigadier literal 分支

### 决策引擎不能被"当前任务 UNKNOWN"无条件阻断
  - **症状**：自动工作完全失效；女仆有确认过的 `AVAILABLE` 候选，仍不切换
  - **根因**：`TaskSwitchDecisionEngine.evaluateNormalSwitch` 中两处 `UNKNOWN → return` 分支会无视候选
    - `:118` `EXTERNAL_UNKNOWN_CURRENT`：当前任务不在 preset 且非 idle
    - `:133`：当前任务在 preset 但 `UNKNOWN`（无 Detector / 兼容策略排除 / 扫描中）
  - **解法（平衡）**：
    1. preset 外 UNKNOWN → 自动工作已启用 + 候选确认 → 允许接管，reason `EXTERNAL_UNKNOWN_REPLACED`
    2. preset 内 UNKNOWN 但仍可用 → 仅当候选更高优先级可切换，reason `HIGHER_PRIORITY_OVER_UNKNOWN`
    3. preset 内已失效（未注册/disabled/被兼容策略排除）→ 任意确认候选可替换，reason `CURRENT_UNSUPPORTED_OR_DISABLED`
    4. 保留 AVAILABLE 优先级保护 + UNAVAILABLE 确认次数 + 反向切换冷却
  - **诊断依据**：将成功切换日志从 DEBUG 提升到 INFO，方便直接观察；reason 字段改为可读枚举（`CURRENT_AVAILABLE`/`HIGHER_PRIORITY_AVAILABLE`/`CURRENT_UNAVAILABLE_CONFIRMING`/`CURRENT_UNKNOWN_KEEP`/`EXTERNAL_CURRENT_REPLACED` 等）

### TaskScanCursor.matches 不应比较 center（follow 模式）
  - **症状**：跟随模式下女仆每移动 4 格就重置整个增量扫描，扫描永完不成
  - **根因**：`matches()` 包含 `center.equals(newCenter)` 或距离比较；非 Home 时 center 随女仆移动变化，每 tick matches 失败 → cursor 重新 start → ring=0
  - **解法**：`matches()` 只比较 `homeMode/horizontalRange/verticalRange`，不比较 center
  - **代价**：cursor 中心在扫描开始时固定，整轮不刷新；正确语义是"扫完这一轮再换中心"，而不是"持续追踪新位置"
  - **联动**：`PATH_BUDGET_EXHAUSTED` 时保存 `cursor.next()`（跳过当前候选），避免密集候选区（树冠、农场）反复卡在同一位置

### MSK 公共 Handler API 与 `IFarmTask` 不能互通
  - **症状**：maidsoulkitchen 0.3.0.9 的 `TaskBerryFarm`/`TaskFruitFarm` 继承自己 `ICompatFarmTask<Handler>`，不是 TLM `IFarmTask` → `instanceof IFarmTask` 检测器恒为 false
  - **根因**：MSK 农场任务用动态 Handler 责任链，但历史代理按 TLM `IFarmTask` 写判定，永远不命中
  - **解法**：
    1. `supports()` 改为 `task instanceof ICompatFarmTask<?>`；handler 链通过 `ICompatFarmTask.getCompatHandler(maid)` 读取
    2. `compileOnly` MSK deobf JAR，避免 `runtimeOnly` 缺失时崩服
    3. Bootstrap 注册时对 `ICompatFarmTask`/`ICompatFarmHandler`/`BerryFruitData` 三个类做 `Class.forName` 存在性检查
  - **副作用**：`getCompatHandler` 可能构建静态 Handler 集合，缓存 handler 引用避免反复构造；版本变化时**只读取** `canHarvest`，禁止写 Brain 缓存

### 资源反向反编译残留物不要提交
  - 调研时为了字节码证据会在 `studio/`、`com/`、`META-INF/` 临时解压依赖 JAR
  - `.gitignore` 必须显式忽略这些目录；本轮补 `/studio/`、`/com/`、`/META-INF/`、`/assets/`、`/data/`
  - 提交前 `git status` 检查是否有新未追踪的 `*.class`、`.class.json`、附属 mod 资源目录

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
