# 开发手册与注意事项

> 注记：本文件自 `0da3fbe`（分支重命名 1.21.1→1.21.1neo 主支、main→1.20.1forge 维护支）起与 `1.21.1neo` 分支分叉，内容以本分支（Forge 1.20.1）为主；平台无关的通用教训可在两条分支间 cherry-pick 互移（口径见 AGENTS.md「文档分层规约」）。

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
│   ├── ConfirmCommand.java       # /tlmconfirm 命令确认
│   ├── CommandClassifier.java    # 命令名单双重判定
│   ├── MaidCommandExecutor.java  # 主人身份命令执行
│   ├── CommandConfirmationService.java  # 确认挂起与会话信任
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
build/libs/tlm_sincerely-1.20.1-forge-0.2.0-beta.jar
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
 - 关键坑：`renderTooltip` 是 override 自 vanilla 的方法，运行时被混淆为 `m_280072_`，注入点**不能写 `remap = false`**（去掉后走 refmap 自动映射）。`remap = false` 只在 userdev 里能过（编译期名字是 `renderTooltip`），发布版必崩 `could not find any targets matching 'renderTooltip'`；而 TLM 自有方法（`taskButtonPressed`、`getTabs`）运行时不混淆，才应保留 `remap = false`

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

### Busy guard：Brain memory 作为"正在干活"证据（T-2）
  - **症状**：`maid_useful_task:maid_tree` 砍树途中被其他 AVAILABLE 普通任务切走；TreeDetector 全扫一轮附近无自然树即报 `UNAVAILABLE`，确认 2 次后任何确认候选都能接管
  - **机制**：`TaskAutoSwitchHandler.observeMaidBusy` 每 tick 观察 `ATTACK_TARGET / WALK_TARGET / PATH / InitEntities.TARGET_POS` 任一 PRESENT → `MaidSwitchState.markBusyObserved`；`TaskSwitchDecisionEngine.evaluateNormalSwitch` 最前经 `suppressedByBusyGuard` 拦截正常切换。观察不依赖切换历史：从未被 auto-switch 切换过的女仆（首次启用、手动/外部设置的任务）同样受保护
  - **不卡死的三个边界**：brain 空闲超过 `BusyIdleForgiveTicks`(60) 放行并结束本次 busy period；`UNAVAILABLE` 确认数达标后最多再 hold `BusyUnavailableHoldTicks`(80)；每段连续 busy period 最多保护 `BusyGuardMaxTicks`(600)，后续经过空闲期再次工作会建立新的 `busyStartTick`
  - **关键约束**：idle 任务必须显式排除（idle 闲逛也有 WALK_TARGET）；攻击抢占走 `handleExperimentalAttackPreempt` 独立路径天然不受影响；`recordSwitch`/`resetForTickRegression` 必须 `resetBusyGuard()`，否则旧任务 busy 证据残留
  - **诊断**：guard 阻止时 `[TaskStability] ... reason=BUSY_GUARD_ACTIVE busyLastTick=... unavailableSinceTick=...`（debug 级）

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

### 硬性工具需求：Detector 允许背包 + 切换前装备
  - **症状**：fishing/shears/extinguishing 检测器只认主手工具，背包有钓鱼竿/剪刀/灭火器仍报 `UNAVAILABLE`，女仆永远不切换
  - **机制**：`HardToolRequirement` + `MaidHardToolService`（`hasAny`/`findBest` 只读查询，`equipTaskRequirement` 装备）；检测器改用 `hasAny` 判定，`CompatDetectorBootstrap` 注册 `taskUid -> 需求` 映射
  - **装备语义**：按 TLM 1.5.3 `TaskEquipUtil.tryEquipFromBackpack` 的相同算法，只操作 `getAvailableBackpackInv()`（不含手部槽），整槽提取目标工具、将原主手写回该槽，再设置新主手。项目声明兼容 TLM ≥1.5.3，因此不直接链接可能晚于最低版本出现的工具类
  - **接线位置**：`TaskSwitchDecisionEngine.switchTo` 在 `maid.setTask` 前调用 `equipTaskRequirement(maid, targetUid)`；若检测缓存过期期间工具已被移走并返回 `MISSING`，本次切换中止。自动 `setTask` 不触发 TLM 的 `onFunctionCallSwitch` 装备回调，所以必须显式装备
  - **不要纳入**：honey（双分支非单一强制，瓶子背包直耗）、locate（主手物品决定目标派生方式，盲装备会改变行为）
  - **边界**：只注册 fishing/shears/extinguishing 三种单一硬需求；`CombinedInvWrapper` 位于 `net.minecraftforge.items.wrapper`，且 `IItemHandler` 本身没有 `setStackInSlot`

### MSM 存储目标不能用 default_storage_blocks tag 代替
  - **症状**：`storage_manage` 长期 `BLOCK_BUDGET_EXHAUSTED` / `FULL_SCAN_NO_STORAGE`，从未返回 `AVAILABLE`，但 MSM 自身能识别并使用仓库
  - **根因**：真实 `PlaceMoveBehavior` 优先读取 `ViewedInventoryMemory.positionFlatten()`；`ItemHandlerStorage.isValidTarget` 只要求 BlockEntity 暴露 `ITEM_HANDLER`。`default_storage_blocks` tag 用于选择交互位置，不是仓库有效性条件
  - **解法**：Detector 反射读取 `VIEWED_INVENTORY` 并优先检查已查看仓库；有效性移除 tag 限制；本地 fallback 垂直范围由 ±7 收窄为附属实际五层扫描

### MSM 版本必须 ≥1.15.x，且 Storage 改名 Target 要双路径兼容
  - **症状**：runClient 中女仆一切入 `storage_manage` 的 PLACE 行为即崩服：`ChatBubbleMgrMixin` 的 `@Shadow getEndTime` 在 TLM 1.5.3 的 `ChatBubbleManger`（已重写为弃用壳）中不存在
  - **根因**：MSM 1.4.1 自身与 TLM 1.5.3 不兼容，与调用方 Detector 无关；MSM 1.15.3 明确适配 TLM 新 AI 系统，1.15.x 的 mixins.json 已删除该条目
  - **解法**：`build.gradle` 升级到 `curse.maven:maid-storage-manager-1210244:7976861`（1.15.6）；Detector 反射 `storage.Storage#getPos` 改为先试旧类、缺失则用 `storage.Target#getPos`（字段方法签名相同）
  - **注意**：MSK 的 cook/berry 反射类不受 MSM 升级影响（MSM 不内嵌 MSK）；升级后 storage_manage 保持可调度，仍需实机验证 PLACE/RESORT 路径

### 灭火只灭生物身上的火，不处理地面火焰
  - TLM `MaidExtinguishingTask.start` 三分支：着火主人（2 格内直接灭，否则走近）→ 女仆自身 → AABB 膨胀 (2,1,2) 内着火驯服动物；地面火焰与着火敌对生物从不在语义内
  - Detector 与之一致：地面点火测试报 `NO_FIRE_TARGET` 是正确行为，不是检测失效；文档已在兼容矩阵注明该边界

### 农耕可达性必须对齐 MaidPathFindingBFS，且区分单点 / 包围盒
  - **症状**：成熟浆果、可可、水果存在，但 Detector 持续返回 `FULL_SCAN_ALL_UNREACHABLE`，实机日志无 `AVAILABLE`
  - **根因**：
    1. `EntityMaid.canPathReach` 的 A* 要求目标格本身是寻路节点，而浆果丛（`SWEET_BERRY_BUSH → DAMAGE_OTHER`）、可可（`COCOA`）、树叶（`LEAVES`）的 malus 都是 -1，永远不会成为节点 → 单点检查恒 false。
    2. TLM/MSK 的真实 AI 用的是 `MaidPathFindingBFS`，而且不同任务语义不同：`MaidFarmMoveTask`（`IFarmTask` 默认脑）只查基准点单点；`MaidFarmSurroundingMoveTask`（TLM 的 `cocoa`、`melon`）查基准点周围 `(-1,0,-1)..(1,1,1)` 的 3×2×3 任一格；MSK `MaidCompatFruitMoveTask` 查的是 `crop.below(searchYOffset)` 这个**基准点**单点，范围/主人距离也判在基准点。
  - **解法**：统一用 `FarmReach.canReach(map, pos, surrounding)`；每次 Detector 扫描复用一个 `MaidPathFindingBFS` 并在 finally 里 `finish()`；路径预算只在命中成熟候选后消费。
  - **坑**：`TaskScanCursor` 一旦绕过中心就会在旧中心扫描；home 模式必须把中心纳入 `matches`（跟随模式中心随女仆移动，不能纳入，否则每 tick 重扫）。
  - **坑**：路径预算耗尽时不要把游标推进到 `nextCursor`，否则该候选整轮被永久跳过；应保持原游标下个 tick 重试。

### `@Mod` 与 `@LittleMaidExtension` 必须拆成两个类
  - TLM 1.5.3 会在 `FMLCommonSetupEvent` 中扫描 `@LittleMaidExtension`，并通过反射再次创建扩展实例；同一个类同时标注 `@Mod` 会导致 Forge 事件监听器、命令和生命周期处理重复注册/执行
  - `SincerelyMod` 只负责 Forge common 配置、mod/Forge event bus、Menu、network 与 server lifecycle
  - `SincerelyExtension` 只负责 `ILittleMaid` 的 Tool、Context、TaskData 等扩展注册
  - client-only 配置界面注册必须放在 `DistExecutor` / `Dist.CLIENT` 分支，公共 `@Mod` 构造路径不能直接解析 Cloth Config GUI 类

### 异步 LLM 维护不能用 ThreadLocal 充当完整请求身份
  - `ThreadLocal` 只能标记同步创建 callback 的瞬间；TLM 的 LLM、Tool 与 history summary 都会跨异步边界
  - TLM 1.5.3 的 `LLMCallback` 在构造时持有同一 `messages` 列表，工具续链复用该对象；可用 callback identity + messages identity 标记整个请求链
  - `HistorySummaryCallback` 与 `AutoGenSettingCallback` 覆写 `onSuccess`/`onFailure`，Mixin 到基类终态方法不会自动覆盖子类覆写；必须分别核实并接线
  - 普通聊天在 history summary 阶段尚未创建最终 callback，因此还要在 `MaidAIChatManager.chat` 的 HEAD/RETURN 跟踪 pending submission，避免维护与摘要后恢复的普通聊天并发
  - 维护超时必须终止当前代次并清除 snapshot/chain；旧 callback 迟到时只能识别为过期请求，不能清理或修改新一轮维护状态

### 维护历史清理必须按消息对象身份，不按长度截断
  - 仅保存 `history.size()` 并删除尾部/头部 N 条会误删维护期间完成的普通对话，也可能残留超时后到达的维护回复
  - 启动维护时保存原 `LLMMessage` 对象的 identity set，结束时只删除不在原集合中的消息
  - snapshot 必须与维护代次绑定或在 timeout 时完整废弃，不能让旧 callback 使用同 UUID 的新 snapshot

### 记忆保存采用“副本修改、落盘成功再提交缓存”
  - `load()` 返回缓存对象副本，调用方不能直接污染共享 cache
  - 保存时按 maid UUID 串行，先把 candidate 写入同目录随机临时文件，再 `ATOMIC_MOVE + REPLACE_EXISTING`；仅成功后替换 cache
  - 保存失败必须保留旧 cache 与旧目标文件，并在 finally best-effort 删除临时文件
  - 损坏/非对象 JSON 要隔离为 `.corrupted.<timestamp>`，不能静默返回空对象后覆盖原文件
  - `ConcurrentHashMap<UUID, MaidMemory>` 只保护映射，不保护 `MaidMemory` 内部 `LinkedHashMap`；不能把共享可变对象直接暴露给异步 Tool

### 自动任务换装与 `setTask` 必须是同一事务
  - 工具交换应快照“完整主手 + 完整目标槽”，整槽提取后交换；不能提取 1 个再覆盖目标槽剩余堆叠
  - `maid.setTask(targetTask)` 抛异常时必须恢复原主手与原槽，否则会出现“任务未切换但工具已变更”
  - 装备阶段内部异常和后续 `setTask` 异常都要走同一个 rollback contract

### 检测缓存中的 UNKNOWN 不能重置或创建 UNAVAILABLE 计时
  - `UNAVAILABLE` 开始/延续 busy guard 的不可用计时
  - `AVAILABLE` 明确清除计时
  - `UNKNOWN/EXPIRED` 只能保留同任务已有计时，不能自行创建计时，也不能把短暂缓存过期解释成任务恢复可用

### 命令执行的名单判定必须沿 `getChild()` 递归（1.5.3 基线核实）
- **构造**：原版 `ExecuteCommand` 用 `literal("run").redirect(dispatcher.getRoot())` 注册 run；实体/条件分支用 `fork` / `redirect`（`redirect` 与 `fork` 都会在节点上设置 redirect）
- **Brigadier 1.1.8 行为**（`CommandDispatcher.parseNodes`）：子节点带 redirect 时，会新建一个以 redirect 目标为根的 `CommandContextBuilder` 并 `context.withChild(...)`，然后**立即返回外层 context**；因此 `ParseResults#getContext()` 只暴露最外层节点（如 `[execute]`），`run` 之后的命令节点全部在 child 链上
- **结论**：判定必须 `getChild()` 递归收集 LiteralCommandNode 名字，否则 `/execute run kill @e` 只能看到 `execute`
- **验证方式**（无需启动游戏）：把编译产物 + brigadier-1.1.8.jar + forge recomp jar 加入 classpath，用反射调用 `CommandClassifier.collectContextNames` 与 `scanTextNames`，对样例集断言：
  - 树判定：`execute run kill @e → [execute, run, kill]`；`execute as Steve run tlmchat hi → [execute, as, run, tlmchat]`；`execute as Steve run execute run kill @e → [execute, as, run, kill]`；`give @s diamond 1 → [give]`
  - 文本判定：`minecraft:kill ... → kill`；`execute in minecraft:the_nether run fill ... → fill`；`tlmconfirm run <uuid> → tlmconfirm`
  - 注意探针里实体选择器参数要用 `word()` 能解析的写法（`@a` 含 `@` 会被 Brigadier 的 unquoted string 拒绝），否则是探针失真而非代码问题
- **文本兜底不可省**：命名空间写法、解析失败、模组命令别名都要靠原文扫描兜住

### 两个 chat HEAD 注入的顺序与 `ci.isCancelled()` 语义
- 同一方法同一注入点的多个 `@Inject` 处理器**共用一个 `CallbackInfo` 实例**；`CallbackInfo#isCancelled()` 是 Mixin 0.8.5 的公开方法（已 javap 核实）
- 处理器执行顺序由 mixin 应用顺序（配置顺序/priority）决定，不是稳定契约；本项目的 `ChatMaintenanceGuardMixin`（维护忙线）**不检查** `isCancelled()`，因此 `CommandConfirmationChatGuardMixin`（命令确认忙线）采取双保险：
  1. 自身 `ci.isCancelled()` 先返回，避免与已取消方重复发言
  2. 维护忙线激活时（`isMaintaining && !isInternalMaintenanceCall`）直接返回，把提示让给维护 Guard，从而**与注入顺序无关**
- 新增注入排在 `ChatMaintenanceGuardMixin` 之后（mixins.json 顺序）作为额外的顺序约定
- 内部维护调用（`MemoryMaintenanceManager.isInternalMaintenanceCall()`）不拦截，避免打断后台整理

### 命令确认挂起的生命周期与 future 完成
- `CommandConfirmationService` 的 pending 表、会话信任、tick 扫描全部只在服务端线程访问；`LLMCallback.addToolResult` 有主线程断言，因此**所有完成路径都必须在服务端线程**：`/tlmconfirm` 命令、tick 扫描、登出事件、停服事件
- 完成顺序固定为：先 `callback.addToolResult(text, toolCallId)`，再 `future.complete(callback)`；future 完成会触发 TLM 的 `handleAsync(..., serverExecutor)`，在服务端线程续跑工具批
- 离开 pending 状态的每条路径都要完成 future：确认、取消、信任、超时、主人登出、女仆死亡/移除（按实体引用判 `isAlive()/isRemoved()`，不用区块查询以免把"未加载"误判为"已移除"）、服务器停止
- `/tlmconfirm` 先移除 pending 再执行，保证 token 一次性、重复点击幂等；token 用 UUID，从不进入模型可见文本，且 `tlmconfirm` 本身在黑名单中

### 审计日志轮转
- 路径用 `FMLPaths.GAMEDIR` 拼 `logs/tlm_sincerely/command_audit.log`（`FMLPaths` 没有 LOGSDIR）
- 每次 append 后 `Files.size` 超限就 `Files.move(..., REPLACE_EXISTING)` 到 `command_audit.log.1`；Windows 上可行是因为写入是独立的一次 open/close，没有常开句柄
- 写盘失败只 WARN，绝不打断命令流程；命令文本按 `\`、`"`、换行/制表符转义，且命令本身已经拒绝换行

### future 完成会内联续跑 TLM 工具批次（再入陷阱）
- `LLMCallback` 用 `handleAsync(..., serverExecutor)` 消费工具的 future；在服务端线程上 `future.complete()` 时 `BlockableEventLoop` 的 `runningTask()` 为假（ServerTickEvent 直接派发，不在 `doRunTask` 内）→ 续跑**同步内联**执行
- 后果：在"迭代 pending 表并逐个 complete"的循环里，同一批次的下一条 `run_command` 可能插入新的 pending → `ConcurrentModificationException`；在 tick 路径会冒泡成崩溃报告
- 规避：所有 pending 遍历一律**先快照、先移除、再完成**（见 `CommandConfirmationService.onServerTick/onOwnerLoggedOut/cancelAll`）
- 相关：`complete()` 若 `addToolResult` 抛异常应 `completeExceptionally`，让 TLM 的 `onToolErrorCall` 兜底生成错误 tool result，而不是让 future 永久挂起

### 命令确认的衍生产出
- 确认消息、气泡、回执一律经 `MaidCommandExecutor.sanitizeForDisplay` 剥离 `§` 与控制字符：模型生成的命令文本可能含格式码，直接进聊天组件会造成视觉伪装
- 挂起时女仆气泡含命令原文（计划要求），该气泡随实体数据同步给能看见女仆的玩家；确认消息本身仍只发主人
- 文本扫描会剥离任意 token 的 `namespace:` 前缀，属保守策略：资源 ID 恰为名单条目时会命中（宁可多确认/多拒绝）

### TLM 数据包 Skill 命名空间必须为 touhou_little_maid
- **现象**：在附属模组的 `data/<modid>/skills/...` 下放置 `skill.md`，TLM 启动后无法扫描到该 Skill。
- **根因**：TLM 1.5.3 的 `SkillsDataReloadListener` 在重载资源时，硬编码过滤了 `resourceLocation.getNamespace().equals("touhou_little_maid")`。
- **解决**：附属若要内置随 mod 数据包分发的 Skill，必须放置于 `data/touhou_little_maid/skills/<skill-name>/skill.md`。
- **调用时机优化**：为防止大型参考 Skill 在初次对话中被模型盲目加载占用上下文，在 Skill 的 YAML `description` 中通过明确限制语（“仅在先前 run_command 失败报错或需构建复杂高阶 1.20.1 指令时调用”），可实现真正的报错自愈式惰性加载。

### Memory harness 的并发、持久化与安全约束
  - 同一 session 同时只能有一个 chat；后续请求返回 `409` 或显式排队，不能让两个 agent loop 共享 history/memory 并发运行
  - record/replay transport 必须按 session 复用，文件名统一 `turn-N.json`；auto-gen 使用独立 transport，不能推进主聊天 cursor
  - SSE 必须监听 response close 并向 agent/transport 传播 `AbortSignal`；客户端断开后不得继续消耗 token 或写入状态
  - Memory CRUD 返回成功前必须真正 await 原子落盘，debounce 只能用于明确允许 best-effort 的路径
  - config/scenario 缩小 `maxMemories` 时，若现有或待载入记忆超限必须拒绝并保持原状态
  - 非回环监听必须要求 token；未知异常默认返回通用错误，完整路径、上游 body 与凭据只写服务端日志
  - Node 原子写使用同目录随机临时文件 + rename replace；Windows 短暂锁定可重试，但不能先删除旧文件制造空窗

### 自动工作：客户端预设库与绑定快照（2026-09-17）
- **调度必须服务端跑 ≠ 预设必须全服共享**：调度只要求数据在服务端可见，因此"给女仆选预设"实现为一次性烤快照（`AutoWorkState.order`，NBT 标签 `bound`）。客户端库是私有文件，服务端只在绑定/首触时读取 id/name/order；避免"某玩家改库，别人女仆跟着变"的隐式共享。旧数据只有 presetId 时按冻结种子补烤。
- **TLM `TaskAttack.isWeapon` 会把弓当近战武器，近战判定不能直接复用**：本模组近战 `attack` 的 `MELEE_WEAPON` 显式排除弓、弩、三叉戟、御币；弓兵（`ranged_attack`）要求弓+箭（`getAllSupportedProjectiles`），弩/戟/弹幕各自按 `isWeapon` 检查。武器注册进 `MaidHardToolService`，`setTask` 前换到主手。
- **棋盘检测：`sitId == 自身` 要视为占用中的可用**：下棋时棋盘被自己占用，若把"被占用"一律判 `UNAVAILABLE`，女仆会被切走并在 `switchTo` 里 `stopRiding` 下车。`BuiltinBoardGamesDetector` 对自身 `sitId` 返回可用，其他占用者判不可用。
- **`canBrainMoving()` 含 `isPassenger()`**：骑乘（椅子/棋盘/船）时它返回 false，不能拿它当"完全不能工作"的判据。需要"坐下/睡眠/拴绳不可工作但骑乘仍扫描"的语义时，先判断 `getVehicle() == null` 再查 `canBrainMoving()`（`BuiltinHoneyDetector` 即此写法）。
- **线格式 encode 上限必须与 decode 上限一致**：解码端对超限（预设 64 条、每条 order 512）抛异常会直接断开连接；编码端要用同一常量做 `Math.min` 钳制、超量静默截断，不能只依赖本地数据的规范性。
- **Jade 为 optional compileOnly**：只有 `AutoWorkJadePlugin` / `AutoWorkJadeProvider` 这类插件类可以引用 Jade API（由 `@WailaPlugin` 在 Jade 存在时加载），其他公共路径不得引入硬依赖，缺失时模组必须正常启动。
- **配置页要即时重绑，不要引入"未应用"中间态**：`AutoWorkConfigScreen` 对该页女仆的增删改序/改名必须直接写本地库并重发 `SetMaidAutoWorkPresetC2SPacket`（`rebakeActivePreset()`），不要做"改动先挂起、点 `<`/`>` 才生效"的提示条——服务端不会自动同步，玩家会以为保存失败。库与绑定仍是两个概念：只有这只女仆的页面在改动时才重绑，其他女仆与推送入库都不受影响。
- **攻击检测不要直接读 `findFirstValidAttackTarget(maid)`**：它读的是 `NEAREST_VISIBLE_LIVING_ENTITIES`/`NEAREST_LIVING_ENTITIES` 记忆，而那份记忆的扫描范围由女仆**当前**任务决定；同时 `maid.canAttack` 会把判定委托给当前任务。结果可能给出"切过去后 TLM 的 `StopAttackingIfTargetInvalid#farAway` 立刻丢弃"的假目标，表现为女仆切入攻击工作后原地发呆。正确做法是用 `IMaidTask.searchDimension/searchRadius` 建搜索盒，并按该任务自己的 `farAway` 语义比较距离（`TaskAttack`：跟随模式按主人到目标；`TaskBowAttack`：女仆到目标），再配合 `attackTask.canAttack` 与 `canSee`。
- **Jade 开发环境会断言插件 UID 翻译**：`JadeClient.onGui` 对每个 `getUid()` 要求存在 `config.jade.plugin_<namespace>.<path>`（本模组即 `config.jade.plugin_tlm_sincerely.auto_work`）。缺 key 时 `AssertionError` 会在加载 overlay 阶段崩客户端（正式包通常不断言）。信息栏正文仍用 `jade.tlm_sincerely.auto_work.active`。

### 2026-09-29 移植主支审查修复（P1×6 + P2）与 MaidFileManager 联动（forge 线，compileJava + build 验证）

- **"Forge correct() 空安全"是错误结论，`defineInList` 的坑两条线都有**：实机整合包（Forge 47.x）`tlm_sincerely-common.toml` 中 `memory = {}` 空表 → `correct()` 对缺失键以 null 调校验器 → 不可变 `List.of(...).contains(null)` 抛 NPE，且 FML 3.0.x 同样只计 "1 errors found" 不进日志。连锁反应比主支那次更直观：配置 NPE → 资源重载失败 → Minecraft "恢复模式"清空全部已选资源包（`Caught error loading resourcepacks, removing all selected resourcepacks`）→ 中文语言包被移除、文本回退英文 → 创建世界时崩溃。修复同主支：`new ArrayList<>(List.of(...))`。**此前 HANDOFF/主支记忆里"Forge 的 correct() 是空安全的、本分支不存在该问题"的判断不成立，两线规则统一：allowedValues 必须可变。**
- **跨 loader cherry-pick 会把 `neoforge.mods.toml` 的改动按文件相似度映射到本分支 `mods.toml`**：git rename detection 落上的是 NeoForge 1.21 语法（`type = "optional"`），Forge 1.20.1 的段格式是 `mandatory = false`。跨线搬运 mods.toml 必须人工核对段格式，不能信 auto-merge。
- **Forge 1.20.1 的 `@EventBusSubscriber` 必须显式 `bus = Mod.EventBusSubscriber.Bus.MOD`**：`AutoWorkConfigReloader` 监听 `ModConfigEvent.Reloading`（IModBusEvent），不写 bus 会被注册到 FORGE 总线导致事件永远不触发（Forge 1.20.1 没有 FML 4.x 的按 IModBusEvent 自动分流）。
- **mixin refmap 只为 vanilla 混淆目标生成条目**：本模组 12 个 mixin 在 refmap 里只有 2 条（`renderTooltip`、`sendSystemMessage`，都是 vanilla 方法），注入 TLM/本模组类目标的条目不需要 SRG remap——对照检查 refmap 时不要把"条目数 ≪ mixin 数"误判为生成缺失。
- **Forge 1.20.1 的 `PacketDistributor` 没有静态 `sendToServer`**：C2S 走 `AutoWorkNetworking.channel().sendToServer(...)`；S2C 单发统一封装在 `AutoWorkNetworking.sendToPlayer(player, payload)`（`channel().send(PacketDistributor.PLAYER.with(() -> player), ...)`），与主支 NeoForge 版同名 API 对齐，减少跨线 cherry-pick 冲突面。
- **平台无关教训自主支 2026-09-28 节互移**（详见主支 DEVELOPMENT.md 同日节，适用本分支）：mixin finalness 用 `@Final` 注解（无成员标记注解，不能写 `@Final(remap = false)`）；TLM `tryCompressBeforeChat` 延迟路径要求维护标记按女仆 UUID 追踪而非仅 ThreadLocal；TLM HTTP 回调线程访问世界状态必须 `server.execute`，且 `shouldStopChat` 静默 return 不触发回调——登记表必须有时间戳兜底清扫；网络包 encode 端计数必须 `Math.min` 到 decode 上限；扫描型 Detector 一律 `getRestrictRadius() - 1`；evidence 字符串匹配用精确常量；`getPersistentData()` 顶层键退出重进保留但死亡重生回退（需跨死亡放 `PlayerPersisted`）；JDK Proxy 不自动执行接口 default 方法（`isAvailable()` 要显式返回 `true`）与 Object 方法。

### 联机进服即断连：命令树 argument type 未进 registry（2026-09-30，双实例复现 + JFR 取证，已结案）
- **症状与表象**：联机（LAN/FRP 服务器）进服数秒即被踢，客户端报 `Internal Exception: io.netty.handler.codec.DecoderException: java.lang.IndexOutOfBoundsException: readerIndex(N)+length(1) exceeds writerIndex(N)`，**客户端 latest.log/debug.log 完全无堆栈**（vanilla `Connection.exceptionCaught` 只 disconnect 不写日志，踢出理由只显示在断开画面）；单人不复现（loopback 同走 wire，但玩家环境数据无触发差异）
- **完整排查链**：round-trip 测试证明 release wire 同 jar 编解码自洽（`releasewire` 测试包，保留）→ 排除 schema 内部缺陷与 1MiB 截断 → 本地双实例（ForgeGradle dev，两实例同 classpath）复现成功排除"两端 jar 混用" → **JFR 异常事件**（`-XX:StartFlightRecording=...,settings=profile` + `jcmd JFR.start jdk.JavaExceptionThrow#enabled=true`，JFR 的 `dumponexit` 要进程退出才落盘、运行中用 `jcmd JFR.dump`）拿到 vanilla 吞掉的完整堆栈：越界点在 **`ClientboundCommandsPacket.readNode` 的 `readVarIntArray`**——是**命令树同步包**，与本模组网络包无关
- **根因**：Forge 47 的命令树包对 argument 节点写的是 `BuiltInRegistries.COMMAND_ARGUMENT_TYPE.getId(serializer)`（registry 数字 id），而 `ArgumentTypeInfos.registerByClass` **只填 BY_CLASS map 不进 registry**——本模组 `UnicodeWordArgument` 只调了 registerByClass，写出 id=-1，客户端反查 null、整棵树字节流错位越界。直接 `Registry.register` 又会在 mod 构造期撞 "Can not register to a locked registry"——**正解是 MOD 总线 `RegisterEvent`（NeoForge 21.1 主支早已用 `DeferredRegister` 修过同一坑，forge 线代码从旧版本 fork 没带上修复；本次已对齐主支 DeferredRegister 模式）**
- **双实例 dev 联机环境（本分支长期可用）**：`build.gradle` runs 块新增 `joiner` run（workingDirectory `run2`、`args --username TesterB`），`gradlew runClient`（host，run/）+ `gradlew runJoiner`（joiner，run2/）即可双开；FG 的 dev mod 通过 `MOD_CLASSES` 环境变量定位（`<modid>%%<classes dir>;...`），手动 java 启动第二实例必须带上，否则 mixin config 资源读不到直接 `MixinInitialisationError`；两实例离线用户名必须不同，否则被 "此名称已被占用" 拒绝（LAN 下前一会话未超时释放也会报这个，稍等重连即可）
- **run/ 目录分支隔离**：默认 workingDirectory 两分支共用 `run/`，曾混入主支 NeoForge jar 导致 forge 启动失败（移入 `.tmp-mdk/run-mods-1.21.1-backup/`）；建议主支把 workingDirectory 改到独立目录实现分支级隔离

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
