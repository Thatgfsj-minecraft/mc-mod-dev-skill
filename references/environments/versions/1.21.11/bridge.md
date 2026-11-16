# MCB 调试桥实现要点（MC 1.21.11 · Fabric · Mojmap）

> 适用环境：MC 1.21.11，Fabric，Mojmap，JDK 21，Loom 1.17.21，Gradle 9.5.1。
> 验证方式：`[实测：编译探针]` = 组织工程 `debug-mcp/bridge-mod/` 全量编译 + remap 通过（Gradle 9.5.1 + loom 1.17.21 + loader 0.19.5，2026-11-15）；`[实测：javap]` = 用 loom 缓存的命名 jar（`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2.jar`）直接核签名，比解包 sources 更快。`[实测：运行时]` = 装进真实实例（HMCL 实例 `thatgfsj`，MC 1.21.11 + Loader 0.18.4 + JDK 21）后对活游戏的端到端调用，2026-11-15。`[实测：反编译源码]` = 直接阅读 1.21.11 Mojmap 反编译源码（`/tmp/mc1211all/net/minecraft/...`）并核对行号，2026-11-17。

> **运行时状态**：v0.5.1 之前的既有 op 已全部实测通过（见文末"运行时验证记录"）；v0.5.1 新增的 4 个 op、`command.run` 的 `side` 扩展与失焦保活守卫目前是**编译探针 / 源码核实，运行时待验证**（见"v0.5.1 增量 op 实现状态"与文末待补记录）。v0.5.2 的 §7 watch 五 op + 两事件（0.3.0）已完成编译探针 + javap 核实，并在 2026-11-17 单世界活游戏上**部分运行时通过**（`watch.await` 逐份游标等；未跑项保持 `[ ]`，见下方"watch 运行时验证记录"）。v0.5.3 的开发者模式弹窗（0.4.0）已完成编译探针 + javap + runClient 双场景实测（部分人工交互项保持 `[ ]`，见"开发者模式弹窗运行时验证记录"）。v0.5.4 的 §8 `world.create`/`world.delete`（0.5.0）已完成编译探针 + 反编译源码核实，并在 2026-11-17 runClient 真机上**四预设建图 / gamerule / 时间天气 / 世界内重建 / 删除与错误码全部通过**（看门狗 TIMEOUT、survival+虚空坠落窗口等未跑项保持 `[ ]`，见文末"v0.5.4 世界创建器 API 核实 + 运行时状态"）。

## 协议实现（参考实现位置）

- 参考实现：`references/debug-mcp/bridge-mod/`（协议规范：`references/debug-mcp/protocol.md`）
- 构建验证：`gradle build` 全绿，产物 `mc-debug-bridge-0.3.0.jar`（remapJar 正常；jar 内含 `WatchSnapshot/WatchRules/WatchSession/WatchEngine.class`）。`[实测：编译探针]`；本节 API 结论以编译探针 + javap / 反编译源码为准。`0.2.1` 构建（2026-11-17 01:58）见历史记录。
- **0.2.0 构建产物（00:54）存在致命缺陷、已废弃**：它的 `@Redirect` 注解在 0.18.4 运行时直接崩溃（见下方"0.2.0 启动崩溃与 0.2.1 修复"）。**不要把 0.2.0 当作可运行版本**。

## 已核实的 API（编译探针 / javap / 反编译源码，移植时直接对照）

| API | 结论 | 验证 |
|---|---|---|
| `net.minecraft.client.Screenshot.takeScreenshot(RenderTarget, Consumer<NativeImage>)` | 存在，**回调式**且同步在渲染线程执行；另有带缩放 int 参数的重载 | `[实测：javap]` |
| `NativeImage.asByteArray` | **不存在**。PNG 字节只能走 `writeToFile(File/Path)`（可写临时文件再读回），或 `makePixelArray()` 拿像素自编码 | `[实测：javap]` |
| `CommandSourceStack.withPermission(...)` | 参数是 `net.minecraft.server.permissions.PermissionSet`（接口，常量 `ALL_PERMISSIONS` / `NO_PERMISSIONS`），**不是 int**——与旧版记忆的 `withPermission(4)` 不同 | `[实测：javap]` |
| `Commands.performPrefixedCommand(CommandSourceStack, String)` | 返回 **void**。要拿成功/失败语义，走 `getDispatcher().parse(String, CommandSourceStack)` + `dispatcher.execute(ParseResults)`（后者抛 `CommandSyntaxException`），检查 `parse.getReader().getRemaining()` 捕获尾随垃圾 | `[实测：javap]` |
| `net.minecraft.commands.CommandSource` 接口 | 四个方法：`sendSystemMessage(Component)` / `acceptsSuccess()` / `acceptsFailure()` / `shouldInformAdmins()`（`alwaysAccepts()` 是 default）。自建命令反馈收集器实现这四个即可 | `[实测：javap]` |
| `ResourceKey.location()` | **已不存在**（`ResourceLocation`→`Identifier` 改名的连带）：取维度字符串用 `level.dimension().identifier().toString()` | `[实测：javap + 编译探针]` |
| `CompoundTag.getAsString()` | **不存在**（`Tag` 只有 `Optional<String> asString()`，对复合标签无用）：SNBT 字符串直接 `tag.toString()` | `[实测：javap + 编译探针]` |
| `com.mojang.authlib.GameProfile.getName()` | 编译不过（authlib 新版访问器变了）；取玩家名用 `player.getName().getString()` 更稳 | `[实测：编译探针]` |
| `MinecraftServer` 的线程投递 | 继承 `ReentrantBlockableEventLoop`，`execute(Runnable)` 可用；集成服务端命令必须投到 server 线程而非 client 线程 | `[实测：javap]` |
| `BlockEntity.saveWithFullMetadata(HolderLookup.Provider)` | 与版本卡主卡结论一致（1.20.5+ 签名），返回 `CompoundTag` | `[实测：javap]` |
| `GameType.getName()` | 存在，返回 `creative` 这类字符串 | `[实测：javap]` |
| `Inventory.getItem(int)` | 存在（1.21.11 未变） | `[实测：javap]` |
| `ClientPacketListener.sendChat(String) / sendCommand(String)` | 两者都存在；`sendCommand` 接收**不带**前导 `/` 的命令串（`/` 开头的消息走 `sendChat` 会被拒绝）——command.run 的多人降级路径用 `sendCommand` | `[实测：javap]` |
| log4j2 `AbstractAppender` 生命周期 | 自建 appender **必须先 `start()` 再 addAppender**——不 start 时 `append()` 被静默跳过（无报错、无日志），这是日志子系统"看起来接好了但什么都不输出"的常见根因 | `[实测：log4j2 生命周期契约]` |
| `Options.pauseOnLostFocus` | public `boolean`，默认 `true`；`Options.java:316`（序列化在 `:1479`，由 `options.save()` 写回 options.txt，`save()` 在 `:1659`）。写入后必须 `save()` 才持久化 | `[实测：反编译源码]` |
| `Options.hideGui` | public `boolean`（`Options.java:717`），F1 同款开关（`KeyboardHandler` 里 `options.hideGui = !options.hideGui`）；抓帧时可临时置 true 并在像素读取后恢复 | `[实测：反编译源码]` |
| `GameRenderer.render(DeltaTracker, boolean)` 的失焦分支 | 方法在 `GameRenderer.java:465`；分支 `:466-472`：`!isWindowActive() && options.pauseOnLostFocus && (!touchscreen() \|\| !mouseHandler.isRightPressed())` 且 `Util.getMillis() - lastActiveTime > 500L` → `:470` `minecraft.pauseGame(false)`；`else` 更新 `lastActiveTime`（字段 `:127`）。**换版本必须重新反编译核实调用点与阈值** | `[实测：反编译源码]` |
| `Minecraft.pauseGame(boolean)` | `Minecraft.java:1561`：仅当 `screen == null` 时 `setScreen(new PauseScreen(...))`（单人未发布存档传 `!bl`，否则 `PauseScreen(true)`）。⚠️ **ESC 也调用它**：`KeyboardHandler.java:554` `this.minecraft.pauseGame(bl2)`（`bl2`=F3 修饰键状态，普通 ESC 为 `false`） | `[实测：反编译源码]` |
| `Minecraft.screen` | `Minecraft.java:360` `public @Nullable Screen screen`——`screen.current` 的 `screen_class`/`screen_title` 来源 | `[实测：反编译源码]` |
| `Screen.getTitle()` | `Screen.java:99` 返回 `Component`（`title` 字段在 `:63`）；`getString()` 得纯文本 | `[实测：反编译源码]` |
| `Minecraft.isWindowActive()` | `Minecraft.java:2617`，**与失焦暂停判定同一个原版谓词**（`screen.current.window_focused` 直接用它） | `[实测：反编译源码]` |
| `Minecraft.gameDirectory` | `Minecraft.java:303` `public final File`，即 `run/` 根；截图落盘目录是 `gameDirectory/screenshots/`（`Screenshot.SCREENSHOT_DIR` 在 `Screenshot.java:25`） | `[实测：反编译源码]` |
| `Minecraft.reloadResourcePacks()` | `Minecraft.java:997`，返回 `CompletableFuture<Void>`（内部私有重载在 `:1001`）；客户端主线程调用 | `[实测：反编译源码]` |
| `Screenshot.grab(File, String, RenderTarget, int, Consumer<Component>)` | `Screenshot.java:27/31`：内部调 `takeScreenshot`，随后把 `NativeImage.writeToFile` 丢给 `Util.ioPool()`，consumer 收到 success/failure `Component`。**异步写盘、时序不可控** → 落盘截图 op 不用它 | `[实测：反编译源码]` |
| `Screenshot.takeScreenshot(RenderTarget, Consumer<NativeImage>)` | `Screenshot.java:64`（缩放重载 `:68`）：同步在渲染线程读像素，consumer 在渲染线程回调；配合 `NativeImage.writeToFile` 自写盘 | `[实测：反编译源码 + javap]` |
| `Util.ioPool()` | `Util.java:213`，后台 IO executor；PNG 写盘放这里，不占渲染线程 | `[实测：反编译源码]` |
| `NativeImage.writeToFile(File)` / `(Path)` | `NativeImage.java:313` / `:342`；`NativeImage` **没有** `asByteArray()`（与既有结论一致） | `[实测：反编译源码]` |
| `ClientPacketListener.sendCommand(String)` | `ClientPacketListener.java:2670`（`sendChat` 在 `:2662`）；参数**不带**前导 `/`——`command.run` 的 `side=client` 走它 | `[实测：反编译源码 + javap]` |
| `MultiPlayerGameMode.getPlayerMode()` | `MultiPlayerGameMode.java:536`，返回 `GameType`；`getName()` 得 `creative` 等（`screen.current.player.game_mode` 用它） | `[实测：反编译源码]` |

## v0.5.1 增量 op 实现状态（编译探针 / 源码核实，运行时待验证）

> `0.2.1`（`mc-debug-bridge-0.2.1.jar`）于 2026-11-17 01:58 构建成功（remapJar 正常）；下表状态 = 编译探针 + 源码核实，**均未做真实游戏运行时验证**。
> 0.2.0 在同一实例上启动崩溃（11:35:53，`@Redirect` 注解编码问题），已由 0.2.1 移除 `@Redirect` 修复；详见下一节。

| 能力 / op | 实现点（参考实现） | 状态 |
|---|---|---|
| 失焦保活（启动关 `pauseOnLostFocus` + 运行期每帧校正） | `McbMod.onInitializeClient()` + `McbClientOps.enforceFocusKeepAlive()`（帧末 `@Inject` 调用；**不用 `@Redirect`**，原因见下节） | 编译探针 + 源码核实；**运行时待验证** |
| `command.run` 的 `side` 路由与 `returnValue`/`messages`/`side` 字段 | `McbServer` dispatch + `McbHandlers.commandRun`（server 路径）/ `ClientPacketListener.sendCommand`（client 路径） | 编译探针 + 源码核实；**运行时待验证** |
| `screen.current` | `McbClientOps.screenJson()`（客户端主线程） | 编译探针 + 源码核实；**运行时待验证** |
| `client.screenshot`（落盘版） | `McbClientOps.requestScreenshot(...)` + `GameRendererMixin` 帧末 `onFrameEnd()` + `McbScreenshot` | 编译探针 + 源码核实；**运行时待验证** |
| `client.pause_on_lost_focus` | `McbClientOps.pauseToggle(Boolean)`（写 `options` + `save()`） | 编译探针 + 源码核实；**运行时待验证** |
| `client.reload_resources` | `McbClientOps.reloadResources(callback)`（`reloadResourcePacks()` future + 30s 看门狗） | 编译探针 + 源码核实；**运行时待验证** |

### Mixin 注解 API 的跨版本差异（实测，直接导致 0.2.0 崩溃）

| 事实 | 结论 | 验证 |
|---|---|---|
| `@Redirect.at()` 返回类型 | `sponge-mixin 0.17.4+mixin.0.8.7`（fabric-loader **0.19.5**，本项目编译 classpath）= `At[]`；`sponge-mixin 0.17.0+mixin.0.8.7`（fabric-loader **0.18.4**，本机运行实例）= 单值 `At` | `[实测：javap 两个 jar 对比]` |
| `@Inject.at()` 返回类型 | 两个版本都是 `At[]` → 跨版本安全 | `[实测：javap]` |
| 后果 | 源码 `at = @At(...)` 编译到 0.19.5 classpath 会编码成 **数组**；拿到 0.17.0 运行时后 MixinExtras `preApply` 把数组当单个 `AnnotationNode` 读 → `ClassCastException: java.util.ArrayList cannot be cast to org.objectweb.asm.tree.AnnotationNode` → `Mixin transformation of net.minecraft.class_757 failed` → 启动崩溃 | `[实测：crash-2026-11-17_01.35.53-client.txt + javap jar 内 GameRendererMixin.class]` |
| 规则 | 跨 Loader 版本分发的模板**不要用 `@Redirect`**；需要拦调用点用 `@Inject(at=@At("INVOKE" ...))`（可 cancellable）或 MixinExtras `@WrapOperation`，并核对运行端 Mixin 版本 | `[实测]` |

### 0.2.0 启动崩溃与 0.2.1 修复（实测）

- **现象**：装入 0.2.0 后启动，`Initializing game` 阶段崩溃；`Description` 显示 `Mixin transformation of net.minecraft.class_757 failed`，根因为 MixinExtras `FactoryRedirectWrapperMixinTransformer` 里 `ArrayList → AnnotationNode` 的 `ClassCastException`。`[实测：crash-2026-11-17_01.35.53-client.txt]`
- **根因**：`GameRendererMixin` 的 `@Redirect(at=@At(...))` 编译期为 `sponge-mixin 0.17.4`（`at()` 是 `At[]`），运行期为 `0.17.0`（`at()` 是单个 `At`）。jar 内 class 反汇编确认 `at=[@At(...)]`，与运行时注解类型不匹配。`[实测：javap]`
- **修复（0.2.1）**：移除 `@Redirect`，改为在已有帧末 `@Inject` 里每帧校正 `Options.pauseOnLostFocus`（`McbClientOps.enforceFocusKeepAlive()`）；重新构建后 jar 内已无任何 `@Redirect` 注解（`grep injection/Redirect` 为空），其余 `@Inject` 注解编码与 0.17.0 一致。`[实测：javap + gradle build]`
- **教训**：见 `pitfalls.md` E5；编译端 Loader（0.19.5）与运行端 Loader（0.18.4）不同时，必须拿运行端实例做一次启动验证，不能把"编译通过 + 目标方法重映射成功"当成可运行。

环境事实补充：

- `mcb.mixins.json` 的 `client` 数组已含 `CameraMixin` / `GameRendererMixin` / `GuiMixin`——引用 `Options` / `Screen` / `Gui` 的 Mixin **必须**在 client 数组，否则专用服务端会 ClassNotFound。`[实测：反编译源码 + 构建配置]`
- `Options.pauseOnLostFocus` 的写入必须紧跟 `options.save()`，否则只影响当前进程、退出即被原版覆盖。`[实测：反编译源码]`
- **Fabric client entrypoint 早于 `options` 赋值**：fabric-loader 0.19.5 `patch/EntrypointPatch.java:549-552` 在 `>=1.19.4` 时把 `startClient` 钩子注入 `Minecraft.<init>` 内、`Thread.currentThread()` 调用之前（`Hooks.startClient` 在此回调 `ClientModInitializer.onInitializeClient`）；1.21.11 `Minecraft.java:438` 先赋 `gameThread`、`:439` 才 `this.options = new Options(...)`。故不能在入口处直接读 `options`——参考实现延迟到首个 client tick（`McbClientOps.scheduleFocusKeepAlive()`，带 ≤20 tick 重试）。`[实测：反编译 fabric-loader 0.19.5 sources + 1.21.11 Minecraft.<init> 顺序]`
- 截图时序：请求在客户端主线程登记 pending → 帧末（`GameRenderer.render` RETURN）读像素 → 恢复 `hideGui` → `Util.ioPool()` 写盘 → 写盘完成后响应。**不要用 `Screenshot.grab`**（异步写盘，无法在像素读完后立即恢复 `hideGui`，也拿不到写盘完成时点）。`[实测：反编译源码 + 实现约束]`

## 天眼自由相机（Mixin 实现要点）

| API | 结论 | 验证 |
|---|---|---|
| `Camera.setup(Level, Entity, ZZF)` | 1.21.11 唯一签名（**不是**旧版的 `update`）；位置与朝向在**这里**写入，`tick()` 只做眼高平滑不改位置 → 覆盖用 `setup` TAIL 注入即可 | `[实测：javap + 字节码反汇编]` |
| `Camera.setRotation(float, float)` | 参数顺序是 **(yaw, pitch)**——先 y 后 x，写反了会朝侧面看 | `[实测：字节码反汇编]` |
| `Camera.setPosition(double,double,double)` / `setPosition(Vec3)` | protected，需 Mixin invoker 或在 `@Mixin(Camera.class)` 内直接调 | `[实测：javap]` |
| `Camera.position()` / `forwardVector()` | public，**这正是 mod 信息栏（Jade 等）读取相机位置与朝向的入口** | `[实测：javap]` |
| `GameRenderer.getFov(Camera, float, boolean)` | 1.21.11 仍是世界投影 FOV 的唯一来源（1.21.2+ 的投影矩阵重构没动它）；private | `[实测：javap]` |
| `ChunkSource.hasChunk(int, int)` | 判断区块是否在客户端已加载——天眼唯一的"拍到虚空"根因 | `[实测：javap]` |
| `ClientLevel.clip(ClipContext)` | 服务端/客户端通用的方块射线；返回 `BlockHitResult`（含方块坐标与命中点） | `[实测：javap]` |
| `Gui` 的按元素私有渲染方法 | 1.21.11 的 HUD 元素是**私有方法**分开的：`renderHotbarAndDecorations` / `renderEffects` / `renderChat` / `renderTabList` / `renderScoreboardSidebar` / `renderBossOverlay` / `renderSubtitleOverlay` / `renderCrosshair` / `renderOverlayMessage` / `renderDemoOverlay` / `renderSleepOverlay`——想按元素屏蔽就逐个 `@Inject(HEAD, cancellable=true)` | `[实测：javap]` |

### 天眼三大坑（血泪教训）

1. **`@Inject` 改返回值必须写 `cancellable = true`**：TAIL 注入里调 `cir.setReturnValue(...)` 而没写这个标志，Mixin 会抛 `CancellationException` **炸掉渲染线程**（报 "The call method_3196 is not cancellable"）。`camera.eye` 一次性模式能侥幸躲过（时序问题），但 `camera.set` 持久模式每帧都过 `getFov`，第一次覆盖必崩。`[实测：crash-2026-11-15_16.35.20-client.txt]`
2. **抓帧的相机覆盖必须在像素读取完成之后才撤销**：提前撤销会抓到"HUD 已恢复"的帧（物品栏/血条又出现了）。正确做法是把 UI 抑制标志**派生自**相机覆盖状态（`uiSuppressed() => active`），覆盖只在读取回调里撤销——单一真相源，不会漂移。`[实测：运行时对比]`
3. **mod 信息栏（Jade/WTHIT）会跟随虚拟相机，但有前提**：Jade 21.1.1 的 `RayTracing.rayTrace` 在 `perspectiveMode: CAMERA` 下用 `camera.position()`（`method_71156`）当射线起点、`camera.forwardVector()`（`method_19335`）当方向——**都是虚拟相机的值**，所以方块高亮框会精确套在虚拟相机准星指着的方块上。**前提是 `displayMode: LITE`**（见下条）。`[实测：Jade 21.1.1 字节码反汇编 + 运行时验证：金块 (20,70,20) 高亮框与 camera.look 返回值完全一致]`
   - ⚠️ 曾经的错误结论：早期文档写"Jade 原理上不跟随虚拟相机"，那是**只反编译到 `player.getEyePosition()` 那条分支**（`perspectiveMode: EYE` 时才走的路径）就下了结论。**读反编译产物要读全分支**，否则容易得出相反的结论。
   - Jade 的射线在 **tick 阶段**（`WailaTickHandler.tickClient`）计算，不是渲染阶段 → 比渲染**滞后一帧**，但持续模式下无感知。

### 补充环境事实

- **Loom 1.17.21 需要 Gradle 9.x 的插件 API**：Gradle 8.14 会构建失败（loom 用了 9.x 才有的 API）。`[实测：构建踩坑]`
- **Jade 的 `displayMode`**：`TOGGLE` / `HOLD_KEY` 需要**按住** HUD 键才显示面板——AI 按不了键，所以自动化调试场景必须改成 `LITE`（配置文件 `config/jade/jade.json`）。⚠️ **游戏会把配置写回原值**（每次退出会覆盖 `jade.json`），改完必须重启游戏才生效，且重启后要再确认一次。`[实测：运行时]`


## 实现侧要点（协议之外的环境事实）

- **零依赖成立**：传输层（JDK `ServerSocket` + Gson）+ log4j2 Appender + 原版类，`fabric.mod.json` 无需声明 fabric-api。`[实测：编译探针]`
- **log4j2 core 在 MC 类路径上**：`org.apache.logging.log4j.core.appender.AbstractAppender` 可直接 extends，attach 到 root logger 无需额外依赖声明。`[实测：编译探针]`
- **桥接端口**：默认 25585，`-Dmcb.port` 覆盖（实现在 `McbMod`）。
- **`fabric.mod.json` 的 loader 下限别写死成开发时的 loader 版本**：入口只用 `ClientModInitializer`（远古稳定 API），参考实现声明 `fabricloader >=0.16.0` 即可。曾因声明 `>=0.19.0` 而在 Loader 0.18.4 的实例上被 Fabric 直接拒绝加载（报依赖不满足）——构建用的 loader 版本 ≠ 运行时实例的 loader 版本，这是"编译全绿但游戏里根本不出现这个 Mod"的头号原因。`[实测：本机 HMCL 实例 Loader 0.18.4]`
- **运行时验证记录（2026-11-15，全部通过）**：装进 HMCL 实例 `thatgfsj`（MC 1.21.11 + Loader 0.18.4 + JDK 21）实测 11 个 op：
  - `hello` → `{protocol:1, loader:"fabric", side:"client", worldLoaded:true}`；`ping` → `pong:true`
  - `status` → overworld、`timeOfDay`、玩家坐标/gamemode/health 正常
  - `block.get` → `fabricaeexnihilo:oak_barrel`，方块实体 NBT 正确读出 `state:"compost"` / `recipeProgress` / `compostLevel` / `stack`
  - `block.scan` → 77 方块；超 4096 体积正确报 `TOO_LARGE`
  - `map.ascii` → 17×17 字符画 + legend；`entity.list` → 玩家实体；`log.history` → 游标推进正常
  - `command.run` → `time query gametime` 返回中文 resultText + `feedback` 数组
  - `screenshot.take` → PNG 1280×683；`log.wait` → 跨连接 `say` 触发后立即匹配（长轮询正常）
  - 已知偏差：`status.game` 返回 HMCL 实例名而非 MC 版本（`getLaunchedVersion()` 语义），建议改用 `SharedConstants.getCurrentVersion().getName()`
- **天眼相机运行时验证（2026-11-15 17:00，PID 53745 实例，全部通过）**：
  - `camera.eye`（一次性）→ 任意坐标任意角度出图 1280×720，`pose`/`chunks` 如实回填（`centerLoaded:true`、`loadedNearby:25/25`）
  - `camera.set` + `fov:45` → **不再崩溃**（`cancellable=true` 修复生效），FOV 覆盖可见（视野明显变窄）
  - 持久模式 UI 抑制生效（物品栏/血条/手消失），`camera.clear` 后视角与 HUD 完全恢复
  - `status.cameraMode` 三态切换正确（none / one-shot / persistent）
  - 坩埚渲染规则验证：满桶熔岩只显示液面（熔岩>固体规则生效）
  - ⚠️ 一次性 `camera.eye` 仍带 HUD → 已定位为时序 bug（见"天眼三大坑"第 2 条），修复后需重启复验

- **运行时验证记录（2026-11-16，天眼第二轮，全部通过）**：新 jar（`a643694`）部署 + 重启后实测：
  - 一次性 `camera.eye` → **完全无 HUD**：物品栏/血条/小地图/F3 调试文字/第一人称手臂/物品名弹窗全部消失，**Jade 方块框保留**
  - `camera.look` 两种参数形式都命中 → `minecraft:grass_block` @ (8,63,8)；`lookAt` 形式 yaw=135/pitch=85.5 与手算一致
  - `camera.set` + `fov:55` 持久模式 → **不崩**，FOV 生效，`status.cameraMode=persistent`，截图正常
  - `camera.clear` → `mode=none`，HUD 与视角完全恢复
  - ✅ **Jade 方块框跟随虚拟相机**：金块 @ (20,70,20) 被精确套框，Jade 高亮方块与 `camera.look` 返回值**完全一致**
  - 补齐的屏蔽项：`Gui.renderDebugOverlay`（F3 文字）、`GameRenderer.renderItemInHand`（手臂，**不在 Gui 里**）、`Gui.renderSelectedItemName`（物品名弹窗）、`Gui.renderSavingIndicator`

- **环境事实：X11 输入注入找游戏窗口**：`xprop -id <id> WM_CLASS` 才能认准游戏窗口——**顶层标题 "THATGFSJ" 其实是 GameViewer（远程串流客户端），不是 Minecraft**。真游戏窗口的 `WM_CLASS` 是 `Minecraft* 1.21.11`（1280x720 的 GLFW 子窗口）。按键必须用 **XTEST**（`libXtst.so.6` 的 `XTestFakeKeyEvent`）注入，LWJGL 忽略 `XSendEvent` 的合成事件。`[实测：X11 注入链路排障]`
- **环境事实：直启游戏要自己拼 classpath**：从 `thatgfsj.json` 的 `libraries` 推导——**部分库（fabric-loader 等）没有 `downloads.artifact` 字段**，只有 Maven 坐标，得按 `group/artifact/version/artifact-version.jar` 拼路径；入口类是 `net.fabricmc.loader.impl.launch.knot.KnotClient`（不是 `Knot`，后者没有 main）。`[实测：直启脚本排障]`

## watch 扩展 API 事实（0.3.0，§7，编译探针 / javap 核实）

> 0.3.0 在 `bridge-mod` 落地 §7 的 watch 会话引擎（`WatchSnapshot` / `WatchRules` / `WatchSession` / `WatchEngine`，见 `debug-mcp/bridge-mod/README.md`）。下表是本轮为 watch **逐个核实过的 1.21.11 API**，属编译探针 / javap 结论；watch 运行时验证记录见本节末尾（2026-11-17，部分通过）。

| API | 结论 | 验证 |
|---|---|---|
| `Minecraft.getSingleplayerServer()` | 返回 `net.minecraft.client.server.IntegratedServer`（可空）；无集成服（多人/主菜单）时 watch 必须回 `WORLD_NOT_LOADED` | `[实测：javap]` |
| `MinecraftServer.getTickCount()` | public int；tick 泵用它检测 tick 变化（变化时才投递一次 `server.execute`） | `[实测：javap]` |
| `MinecraftServer.getLevel(ResourceKey<Level>)` | public；返回 `ServerLevel` 或 null（null = 维度未加载/不存在 → `WORLD_NOT_LOADED` / `dimension_unloaded`） | `[实测：javap]` |
| `MinecraftServer.getPlayerList()` / `isRunning()` / `isStopped()` | 均 public；`execute(Runnable)` 继承自 `BlockableEventLoop`（线程安全投递），`isSameThread()` 也在该类 | `[实测：javap]` |
| `PlayerList.getPlayers()` | `List<ServerPlayer>`；无 `dimension` 参数时取第一个玩家的维度（server 线程） | `[实测：javap]` |
| `ServerPlayer.level()` | **override 返回 `ServerLevel`**（Entity 的 `level()` 返回 `Level`）；取玩家维度 `level().dimension()` | `[实测：javap]` |
| `Level.isLoaded(BlockPos)` | public；实现 = 边界检查 + `getChunkSource().hasChunk(cx, cz)`（纯查询，**不加载**）。`Level.getBlockState` 内部走 `getChunk(...)`，所以必须先 isLoaded | `[实测：javap + 反编译源码]` |
| `ServerChunkCache.hasChunk(int,int)` / `ChunkSource.hasChunk(int,int)` | public；区块保护的等价底层查询 | `[实测：javap]` |
| `Level.getBlockState(BlockPos)` | 越界返回 `VOID_AIR`；未加载区块会走 `getChunk(...)`（可能触发加载）→ **禁止裸调** | `[实测：反编译源码]` |
| `EntityGetter.getEntitiesOfClass(Class, AABB, Predicate)` | 接口 default 方法；`Level` 实现了 `getEntities(Entity, AABB, Predicate)` 后可直接用；**包含玩家**（`exclude_players` 自行过滤） | `[实测：javap]` |
| `ServerLevel.getEntities()`（无参） | **protected**，不能用；按 §7.10.4 用 public 的 `getEntity(int)` 做 out_of_bounds/removed 辅助判定 | `[实测：javap]` |
| `ServerLevel.getEntity(int)` / `players()` | 均 public；`players()` 返回 `List<ServerPlayer>` | `[实测：javap]` |
| `Entity.getUUID() / getId() / getType() / isRemoved() / isAlive() / position() / blockPosition() / getName()` | 均 public；UUID 主键、runtimeId 仅辅助、type id = `BuiltInRegistries.ENTITY_TYPE.getKey(type)` | `[实测：javap]` |
| `LivingEntity.getHealth()` | 返回 **float**（报告里转 double） | `[实测：javap]` |
| `EntityType.is(TagKey<EntityType<?>>)` / `builtInRegistryHolder()` | 均 public；entity `#tag` 过滤用 | `[实测：javap]` |
| `BlockState.getBlock()/isAir()/getFluidState()/getProperties()/getValue(Property)` | 均存在（`getProperties/getValue` 在 `BlockBehaviour.BlockStateBase` 继承的 `StateHolder` 上）；属性值 `toString()` | `[实测：javap]` |
| `BlockPos.asLong()` / `asLong(int,int,int)` / `getX(long)` / `getY(long)` / `getZ(long)` | 均存在；采样数组按 `asLong` 键 + 解码做有序 merge（不建逐方块对象） | `[实测：javap]` |
| `BlockEntity.saveWithFullMetadata(HolderLookup.Provider)` | `final CompoundTag`；快照 `include_nbt` 用 `level.registryAccess()`，SNBT 用 `tag.toString()` | `[实测：javap]` |
| `ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim))` / `Identifier.parse` | 均存在；`Registries.DIMENSION` 也在 | `[实测：javap]` |
| `ResourceKey.location()` | **不存在**（取维度字符串用 `key.identifier().toString()`，与主卡结论一致） | `[实测：javap + 编译探针]` |
| `AABB(double,double,double,double,double,double)` | 存在；区域实体查询包围盒 = 每轴 `[min, max+1)` | `[实测：javap]` |
| 零第三方依赖 | 0.3.0 仍不引入 fabric-api / fastutil 等；只新增 4 个内部类，`gradle build` 全绿且 jar 内出现 `Watch*.class` | `[实测：编译探针]` |

### watch 运行时验证记录（§7，2026-11-17）

> 环境：MC 1.21.11 · Fabric loader 0.18.4 · JDK 21 · HMCL 实例 `thatgfsj`（重型模组包）· 集成服务端单人世界；bridge-mod **0.3.0**（含逐份游标修复 commit 69f77f5）。`[实测：运行时]`
> 纪律：未在活游戏端到端跑过的保持 `[ ]`；一次客户端崩溃（03:48:35）为 Litematica 自身守护线程终止，栈内无 MCB/watch，属环境既有问题，未归因于 watch。

**已通过**

- [x] `watch.start`：`side:"server"`、`non_air_count` / `entity_count` / `truncated_chunks` / `seq:0` 合理（实测 non_air_count=258、entity_count=1、truncated_chunks=[]）。
- [x] `watch_id` 重复 / 含 `.` → `BAD_PARAMS`；体积 >32768 → `TOO_LARGE`；第 9 个活跃会话 → `TOO_LARGE` + `watch session limit reached`；未知规则 type → `RULE_INVALID`。
- [x] `after_seconds(3.0)` + TNT（`fuse:10`）：matched、`trigger_reason="after_seconds(3.0)"`、`elapsed_ticks=60`；summary_diff `total_changed=46`、`affected_bounds` [24,100,24]–[32,104,32]、`max_radius_from_origin=4.58`、`transitions` stone→air 46、`sample_changes` 截断（`truncated:true`、`truncated_count:36`）；实体 pig `hp_before=10` / `hp_after=0` / `status=removed`。
- [x] `settle_quiet(quiet_ticks=10)`：变化后静默结算，`trigger_reason=settle_quiet`。
- [x] `on_condition`：`block_match`（stone→air，reason 形如 "on_condition(block_match...)"）与 `entity_hp_below`（对玩家 UUID、threshold 21 立即命中，reason 形如 "on_condition(entity_hp_below...)"）。
- [x] `interval(5)`：`trigger_reason=interval(5)#n`；逐份游标修复后连续 await 依次拿到 seq 1/2/3 且 `next_seq==report.seq`，第 4 次 `matched:false`；`max_count` 用尽后会话结束。
- [x] `repeat`（after_seconds 0.5，max_count 2）：连续两份报告 seq 1/2，`trigger_reason="repeat#1:after_seconds(0.5)"`。
- [x] 报告模式：`summary_diff`（上述爆炸）；`timeline_events`（空窗口 `events:[]`、`counts:{}`、`truncated:false`）；`snapshot` 模式 blocks/entities 列表与 `detail_limit`。
- [x] `watch.snapshot`：`trigger_reason:"snapshot"`、`seq` 不 +1、不入缓冲、规则状态不变。
- [x] `watch.list`：活跃会话 metadata（bounds / dimension / elapsed_ticks / rule_type / auto_stop / buffered_reports / seq / phase）正确。
- [x] `watch.await`：`matched:false` 正常超时；逐份游标（interval 场景）；缓冲 >64 时 `overflowed:true`（实测 `next_seq=83`）。
- [x] `watch.stop`：owner 返回 `stopped:true`；非 owner → `BAD_PARAMS` 含 `not the owner`（双连接实测）。
- [x] 事件推送：观察连接收到 `watch.triggered`（带 report）与 `watch.stopped(auto_stop)`。
- [x] `watch.await` 对已关闭会话无新报告立即 `matched:false`（实现约定，已写入协议 §7.4）。

**未验证 / 待补**

- [ ] 多人世界 / 主菜单：`WORLD_NOT_LOADED`（message 含 `watch sessions require an integrated server (server-authoritative sampling)`）。
- [ ] 未加载区块保护：`truncated_chunks` 非空、不强制加载（本轮测试区域已 forceload，未触发）。
- [ ] `on_condition.block_state` / `entity_removed`；`repeat.until`；`any` / `all(latched|simultaneous)` / `sequence` 组合与 `trigger_trace`。
- [ ] `timeline_events` 的非空事件顺序与 `pos_epsilon` / `hp_epsilon` 降噪、`counts` 完整而 events 截断。
- [ ] `settle_quiet` 的 `max_wait_seconds` timeout 分支（`timed_out:true`）。
- [ ] 60s 报告保留期后 `WATCH_NOT_FOUND`；auto_stop 后迟到 await 仍可取。
- [ ] 连接断开 60s 清理 + `watch.stopped(client_disconnected)`；维度卸载 / 集成服退出 / 换世界；interval 300s 空闲 `idle_timeout`。
- [ ] 慢客户端事件丢弃时 `status.droppedEvents` 增长。

## 开发者模式弹窗 API 事实（0.4.0，javap / runClient 核实）

| API | 结论 | 验证 |
|---|---|---|
| `ConfirmScreen(BooleanConsumer, Component title, Component message)` / 五参重载（自定义是/否按钮文案） | 均存在；三参构造默认用原版“是/否” | `[实测：javap]` |
| `ConfirmScreen.layout` / `yesButton` / `noButton` / `callback` | `layout` 是 protected `LinearLayout`，是/否按钮由 `init()` 的 `addButtons(LinearLayout)` 加入；`callback` protected final | `[实测：javap + sources]` |
| `ConfirmScreen.init()` / `repositionElements()` | 先 `super.init()`（布局居中）再追加自定义控件；重写 `repositionElements()` 在 `super` 之后按 `layout.getY()+layout.getHeight()` 定位复选框 | `[实测：sources + 编译探针]` |
| `ConfirmScreen.keyPressed` ESC | `shouldCloseOnEsc()==false`；ESC 在 `keyPressed` 里显式 `callback.accept(false)`——因此 ESC 等价于【否】且不会重复弹窗 | `[实测：sources]` |
| `Screen.repositionElements()` / `addRenderableWidget()` / `font` | 均存在；窗口 resize 会重建控件，复选框在 `init` 中重新添加并按布局重定位 | `[实测：javap]` |
| `Checkbox.builder(Component, Font)` | 构造器 package-private，公开 builder：`.pos(x,y).maxWidth(n).selected(b).onValueChange((cb,v)->...).build()`；`selected()` 可读 | `[实测：javap + sources]` |
| `Minecraft.screen` / `setScreen(Screen)` / `getOverlay()` | `screen` public 字段；`getOverlay()` 在加载遮罩存在时非 null（弹窗守门条件） | `[实测：javap]` |
| `Minecraft.tick()` | `public void`；用 client Tick RETURN 的 `Inject` 做四重守门（不新增 fabric-api 依赖） | `[实测：javap + runClient]` |
| `FabricLoader.getInstance().getConfigDir()` / `getModContainer(String)` | 前者返回 config 目录；后者返回 `Optional<ModContainer>`；`getMetadata().getName()/getId()` 供弹窗来源显示 | `[实测：javap]` |
| `TitleScreen` | 主页面类；条件用 `screen instanceof TitleScreen` 兼容“扩展 TitleScreen 的自定义主界面”；别的 Mod 弹窗占屏时继续等待 | `[实测：runClient + 源码]` |
| Mixin 位置 | 新增 `MinecraftMixin` 必须放进 `mcb.mixins.json` 的 `client` 数组；只 `Inject` 不 `Redirect`（跨 Mixin 版本安全，见红线 10） | `[实测：runClient 启动 + 编译探针]` |

## 开发者模式弹窗运行时验证记录（0.4.0，2026-11-17）

> 环境：`gradle runClient` 开发客户端（MC 1.21.11 · Fabric Loader 0.19.5 · JDK 21 · mixin 0.17.4），mcb 0.4.0，测试端口 25600（避开活游戏 25585）。`[实测：运行时]`
> 纪律：人工交互项未自动化的保持 `[ ]`；不把“编译通过”写成“运行时通过”。

**已通过**

- [x] `-Dmcb.devMode=true`：日志 `dev mode from JVM property` → `ENABLE_SILENT`；**无弹窗**；端口立即监听；raw TCP `hello/status/watch.list` 正常。
- [x] `-Dmcb.devMode=false`：`DISABLE_SILENT`；无弹窗；端口不监听；`run/options.txt` 的 `pauseOnLostFocus` 保持 `true`（**修复点**：帧末守卫曾未按开发者模式收口，会把选项改成 false；现由 `McbMod.isDevModeActive()` 守门）。
- [x] 无参数 + 无配置：加载完进入 TitleScreen 后弹一次独立 `ConfirmScreen`（截图核对：标题/来源/正文/是/否/“记住本次选择”均在；主界面布局未被改动）；点【是】前端口不监听。
- [x] ESC /【否】：返回 parent TitleScreen；端口仍不监听；再次等待后未重复弹窗（`prompt shown` 计数保持 1）。
- [x] 鼠标点【是】：`dev-mode prompt answered: dev_mode=true` → 端口监听、`pauseOnLostFocus disabled`、`developer mode activated`。
- [x] 勾选“记住本次选择”再点【否】：`config/mcb-bridge.properties` 写入 `remember_choice=true / dev_mode=false`；下一次启动 `dev mode from remembered config: dev_mode=false`，无弹窗、无端口。
- [x] 配置 `dev_mode=true`：静默 `ENABLE_SILENT`，端口监听、无弹窗。
- [x] 优先级 1 覆盖 2：配置 `dev_mode=true` + `-Dmcb.devMode=false` → 仍 `DISABLE_SILENT`、无端口。
- [x] 非法 JVM 值：`-Dmcb.devMode=bogus` + 配置 `dev_mode=true` → WARN “ignoring invalid” 后按配置静默开启（符合“非法值按未传处理”）。
- [x] 配置损坏 + 来源覆盖：`remember_choice=maybe` + `source_mod_name=Host Project Mod` → 降级 `ASK_ON_TITLE` 并弹窗，来源显示 `Host Project Mod（mcb）`。
- [x] `activateDevMode()` 幂等：AtomicBoolean 保证重复调用不再开端口/不再改选项（代码审查 + 单次激活日志）。
- [x] `gradle build` 全绿；`mc-debug-bridge-0.4.0.jar` 含 `McbDevModeConfig/McbDevModeScreen/MinecraftMixin`；`fabric.mod.json` 版本 0.4.0。

**未验证 / 待补（人工交互）**

- [ ] 进出存档退回主页面不重复弹窗（单次标志代码路径已保证，但未在“加载世界→退出到标题”链路上跑过）。
- [ ] 与 Mod Menu / FancyMenu / ReplayMod 等主界面美化 Mod 共存（本 runClient 仅 5 个基础 mod；设计上不碰 TitleScreen 布局，仍需实机确认）。
- [ ] 点【否】后在真实世界里切出窗口应正常暂停（本轮只核对了 `pauseOnLostFocus=true` 与未执行守卫日志，标题屏无法触发暂停）。
- [ ] 点【是】后切出窗口不暂停/不进 ESC 的真实世界行为（沿用 v0.5.1 失焦保活，未在本轮重新跑世界内验证）。
- [ ] 窗口 resize / GUI scale 下确认屏复选框与按钮的重排（本轮仅默认 854x480）。
- [ ] 生产 remap jar 装进 HMCL 实机（本轮只跑 runClient 开发映射；Mixin 的 `Inject` 在两侧均验证过同类路径）。

## 运行时验证记录（v0.5.1，待补 —— 可追加段落）

> 本节是**可追加占位**：v0.5.1 的新能力需要 Lead 在真实实例（活游戏）上做端到端验证后再落笔；未跑过的项保持 `[ ]`，**不许**把"编译通过"写成"运行时通过"。
> 记录格式建议：日期 / 实例与 PID / 请求参数 / 实际响应摘要 / 证据（截图路径、日志 row）/ 与规格的偏差。

- [ ] **失焦保活**：窗口失焦 >500ms 世界不暂停（`screen.current.window_focused=false` 且 `screen_class=null`）；`screen.current.pause_on_lost_focus=false`
- [ ] **ESC 回归**：失焦保活生效时按 ESC 仍能打开 PauseScreen（守卫不能误伤 ESC 路径）
- [ ] `screen.current`：在世界内 / 主菜单 / PauseScreen 三种状态下 `in_world` / `screen_class` / `screen_title` / `window_focused` / `player` 如实
- [ ] `client.screenshot`：落盘到 `run/screenshots/`、`path` 绝对路径存在且非空、`bytes`/`width`/`height` 与文件一致；`hide_hud=true` 时该帧无 HUD 且下一帧 HUD 恢复；`filename` 非法字符被过滤
- [ ] `client.pause_on_lost_focus`：无参只读；写 `enabled` 后 `options.txt` 持久化、guard 联动；恢复原版行为后失焦确实暂停（兼容性回归）
- [ ] `client.reload_resources`：`reloaded=true`、`duration_ms` 合理；改资源包后热重载生效；错误资源包 → 日志可检索（`Missing`/`Unable to load model`）
- [ ] `command.run` `side`：`server` 返回 `returnValue` 为整数且 `feedback===messages`；`client` 路径 `resultText=""`/`messages=[]`/`returnValue=null`；`auto` 路由正确；失败路径 `success=false`
- [ ] 超时路径：`client.screenshot` 10s / `client.reload_resources` 30s 看门狗各只回一个响应（`responded` 生效）

## v0.5.4 世界创建器 API 核实 + 运行时状态（0.5.0，§8）

> 参考实现：`references/debug-mcp/bridge-mod` 的 0.5.0（新增 `McbWorldOps.java`，`McbServer`/`McbClientOps` 各加少量派发与 tick 泵）。验证分两轮：① `gradle build` 编译探针 + 1.21.11 Mojmap 反编译源码核实；② **2026-11-17 runClient 真机验收**（`-Dmcb.devMode=true`，Gradle 9.5.1 / JDK 21.0.13，窗口 :0）：4 预设建图、gamerule/时间/天气、世界内重建（clean_if_exists）、删除与错误码、`world.progress` 均实测通过，MCP 侧 `MCB_LIVE=1 node smoke.mjs` 与 `node integration.mjs` 也全绿。未跑项保持 `[ ]`，不得当成"已实测"。
> 源码位置：`/tmp/mc-src`（1.21.11 Mojmap 反编译；如被清理可从 loom 缓存的 `-sources.jar` 重新解压）。

### 已核实的 1.21.11 API（编译探针 + 反编译源码）

| API | 结论 | 验证 |
|---|---|---|
| `WorldOpenFlows#createFreshLevel(String, LevelSettings, WorldOptions, Function<HolderLookup.Provider, WorldDimensions>, Screen)` | public（`WorldOpenFlows.java:81`）。内部完成 pack 加载 → 维度 bake → `Minecraft#doWorldLoad`；**失败不抛异常**，只 `LOGGER.warn` + `levelStorageAccess.safeClose()` + `minecraft.setScreen(parent)`（:104-108）→ 实现用"返回后 `getSingleplayerServer()==null`"判定失败 | `[实测：反编译源码]` |
| `LevelSettings(String, GameType, boolean hardcore, Difficulty, boolean allowCommands, GameRules, WorldDataConfiguration)` | 7 参构造（`LevelSettings.java:19`）；`allowCommands()/getDataConfiguration()/gameType()/difficulty()` 均存在（:38/:60/:68） | `[实测：反编译源码 + 编译探针]` |
| `new GameRules(FeatureFlagSet)` / `set(GameRule<T>, T, @Nullable MinecraftServer)` | 构造器在 `GameRules.java:96`，`set` 在 :109；`configure` 阶段传 `server=null` 合法（只改值不通知） | `[实测：反编译源码 + 编译探针]` |
| gamerule 常量与包名 | 1.21.11 在 **`net.minecraft.world.level.gamerules.GameRules`**（不是旧的 `net.minecraft.world.level.GameRules`）：`ADVANCE_TIME`:20、`ADVANCE_WEATHER`:21、`FIRE_SPREAD_RADIUS_AROUND_PLAYER`:33、`KEEP_INVENTORY`:38、`SPAWN_MOBS`:73；**已无 `doFireTick`** | `[实测：反编译源码 + 编译探针]` |
| `WorldOptions(long, boolean generateStructures, boolean bonusChest)` | 3 参公开构造（`WorldOptions.java:31`） | `[实测：反编译源码 + 编译探针]` |
| `WorldPresets.createNormalWorldDimensions(provider)` / `createFlatWorldDimensions(provider)` | 均 public（`WorldPresets.java:60/:68`） | `[实测：反编译源码 + 编译探针]` |
| `WorldDimensions#replaceOverworldGenerator(HolderLookup.Provider, ChunkGenerator)` | public（`WorldDimensions.java:61`）；只替换主世界，其余维度保留 | `[实测：反编译源码 + 编译探针]` |
| `FlatLevelGeneratorSettings(Optional<HolderSet<StructureSet>>, Holder<Biome>, List<Holder<PlacedFeature>>)` | 公开构造（`FlatLevelGeneratorSettings.java:104`）；`getLayersInfo`:189 / `updateLayers`:197 / `createLakesList`:229；`updateLayers` 之后 `voidGen` 才正确（全空气 → void 世界） | `[实测：反编译源码 + 编译探针]` |
| `FlatLevelSource#createState` 的结构集回退 | `structureOverrides()` 为 `Optional.empty()` 时回退"注册表里**全部**结构集"（`FlatLevelSource.java:44-48`）→ **超平坦关结构必须显式 `Optional.of(HolderSet.empty())`**，只靠 `WorldOptions.generateStructures=false` 不够稳 | `[实测：反编译源码]` |
| `FlatLayerInfo(int height, Block block)` | 与 `FlatLevelGeneratorSettings.getDefault` 内部用法一致（1×基岩+2×泥土+1×草方块） | `[实测：反编译源码]` |
| `ServerLevel#setDayTime(long)` / `setWeatherParameters(int clearTime, int weatherTime, boolean raining, boolean thundering)` / `getGameRules()` | 均 public（`ServerLevel.java:470 / :315 / :1772`）；`setWeatherParameters` 直接写 `ServerLevelData` 的 clear/rain/thunder 计数器 | `[实测：反编译源码 + 编译探针]` |
| `MinecraftServer#setDifficulty(Difficulty, boolean)` / `getLevel(ResourceKey<Level>)` / `getWorldPath(LevelResource)` / `isReady()` / `isShutdown()` | 均 public（:1302 / :1202 / :1960 / :1423 / :1190） | `[实测：反编译源码 + 编译探针]` |
| **1.21.11 世界出生点重构** | `getSharedSpawnPos()/setDefaultSpawnPos()` **已不存在**，改为 `MinecraftServer#getRespawnData()` 返回 `LevelData.RespawnData(GlobalPos, yaw, pitch)`（`LevelData.java:43`，`pos` 是**脚部**坐标，`DEFAULT` = `(OVERWORLD,(0,0,0))`）；写入口 `setRespawnData(RespawnData)`（:1710）/ `RespawnData.of(key, BlockPos, yaw, pitch)`（:64） | `[实测：反编译源码 + 编译探针]` |
| 新世界出生点写入时机 | `MinecraftServer#setInitialSpawn` 在服务器初始化阶段写入（`MinecraftServer.java:478-520`）：先取 `sampler().findSpawnPosition()` 区块、`getSpawnHeight`，再按 `getSpawnPosInChunk` 微调；**虚空世界会落到 minY 附近**。玩家实际落点再由 `PlayerSpawnFinder.fixupSpawnHeight` 兜底（`PlayerSpawnFinder.java:96-115`：无碰撞则升到 maxY 再降到 minY 再上 1 格）→ 空世界约 `y=minY+1`，因此实现必须主动写 `RespawnData` + 平台 + 兜底传送 | `[实测：反编译源码]` |
| `Minecraft#createWorldOpenFlows()` / `getLevelSource()` | public（`Minecraft.java:2023 / :1085`）；`getLevelSource()` 即 `LevelStorageSource`（`getLevelPath(String)`:312 / `levelExists`:304 / `isNewLevelIdAcceptable`:293 均 public） | `[实测：反编译源码 + 编译探针]` |
| `Minecraft#disconnectFromWorld(Component)` / `disconnect(Screen, boolean)` | `disconnectFromWorld`:2086（本地世界 → 保存屏 → `TitleScreen`；多人 → `JoinMultiplayerScreen`）；`disconnect(Screen,boolean)`:2121 内部 `while(!integratedServer.isShutdown()) this.runTick(false)`，**阻塞客户端线程**，返回时 `MinecraftServer.stopServer` 已 `storageSource.close()`（:684）→ 存档锁释放 | `[实测：反编译源码]` |
| `ServerPlayer#teleportTo(ServerLevel, double,double,double, Set<Relative>, float yaw, float pitch, boolean)` | public（`ServerPlayer.java:1666`）；`Entity#resetFallDistance()` 可用于传送后清零坠落距离 | `[实测：反编译源码 + 编译探针]` |
| `Difficulty` / `GameType` 枚举 | `Difficulty`: peaceful/easy/normal/hard（`Difficulty.java:12-16，`byName`:46 可空）；`GameType`: survival/creative/adventure/spectator（`GameType.java:17-20，`getName()`:45） | `[实测：反编译源码 + 编译探针]` |
| 零第三方依赖 | 0.5.0 仍不引入 fabric-api；`gradle build` 全绿，`mc-debug-bridge-0.5.0.jar` 内含 `McbWorldOps*.class` | `[实测：编译探针]` |

### §8.3 两处必须写明的映射等价

- **`fire_tick=false` → gamerule `fire_spread_radius_around_player=0`**：1.21.11 已删除 `doFireTick`。该整数规则语义为 `-1` = 不限制蔓延范围、`0` = 只有玩家贴身时才会蔓延（等价"基本关闭"）、正数 = 限制在该半径内；参考实现取 `0`，`fire_tick=true` 时取 `-1`。
- **`do_mob_spawning=false` → gamerule `spawn_mobs=false`**，难度保持 `normal`（和平难度下 `/summon` 的敌对测试怪会立即消失，不能作为"安全"默认）。

### 运行时状态（0.5.0，2026-11-17 runClient 真机验收）

> 验收方式：Gradle 9.5.1 / JDK 21.0.13 起 `runClient`（`JAVA_TOOL_OPTIONS="-Dmcb.devMode=true -Dmcb.port=25585"`），用裸 TCP NDJSON 客户端逐条断言，MCP 侧再跑 `MCB_LIVE=1 node smoke.mjs` / `node integration.mjs`。下列 `[实测]` 均为本轮通过；未跑项保持 `[ ]`。

- [实测] 4 预设各建图成功，`spawn` 为实际出生点：`bedrock_lab` = `(0,-61,0)`、`flat_clean` = `(0,-60,0)`、`void_platform` = `(0,65,0)`、`vanilla_quiet` = 原版随机地表（本轮 `(96,136,-32)`）。超平坦原地形：`bedrock_lab` Y=-64/-63/-62 均为基岩；`flat_clean` 玩家落在 y=-60。
- [实测] `vanilla_quiet` 固定 seed 12345 生效（响应 `seed=12345`，主世界维度 `minecraft:overworld`）。**未直接扫描村庄/矿脉以证明结构确实关闭**（`generate_structures=false` 走 `WorldOptions`，结构关闭的逐项扫描保留 `[ ]`）。
- [实测] 进世界后用 `command.run` 查询：`spawn_mobs=0` / `advance_time=0` / `advance_weather=0` / `keep_inventory=1` / `fire_spread_radius_around_player=0`，`timeOfDay=6000`、不雨不雷。**未重启回读 `level.dat` 验证落盘**（世界内读到的即已加载的 level data，落盘回读保留 `[ ]`）。
- [实测] 默认创造模式进入（`player.gamemode=creative`）；`allow_commands=true` 生效（`command.run` 成功并拿到 `returnValue`）。`difficulty=normal` 时 `/summon zombie` 不消失未单独跑（保留 `[ ]`）。
- [实测] `void_platform`：`(0,64,0)` 与四角 `(±4,64,±4)` 等 9×9 为基岩、`(5,64,0)` 为空气；世界出生点 `(0,65,0)`；玩家被正确放到平台上（y≈65，未坠虚空）。**survival + 已坠落玩家的传送救援路径未跑**（默认创造无此窗口，保留 `[ ]`）。
- [实测] 在世界内调用 `world.create`：先 save-and-quit 回标题屏再建图成功；`clean_if_exists=true` 同名连续两部分两轮（旧存档删除后重建）成功；`clean_if_exists=false` + 已存在 → `WORLD_ALREADY_EXISTS`，且**不会把玩家踢出当前世界**。
- [实测] `world.delete`：运行中未 `force` → `WORLD_IN_USE`；`force=true` 先退出再删；非运行存档直接删除；三种路径都返回 `deleted:true`，目录消失。
- [实测] 安全红线：手工目录（非 `mcb_` 且无 marker）→ `WORLD_DELETE_FORBIDDEN`。**`.mcb-world.json` 让非 `mcb_` 名字可删的正向路径未单独跑**（保留 `[ ]`）。
- [实测] 错误码：`WORLD_NOT_FOUND` / `WORLD_ALREADY_EXISTS` / `PRESET_INVALID` / `BAD_PARAMS`（名字含 `../`）均按契约返回。`lock_time` / `timeoutMs` 越界的 `BAD_PARAMS` 未单独跑（保留 `[ ]`）。
- [实测] 单飞与响应顺序：`WORLD_ALREADY_EXISTS` 失败响应后紧接的 `world.create` 不再被误判 `WORLD_IN_USE`（修复前会撞车，见"实测中发现并修复"）。真正两个并发请求的竞态未单独构造（保留 `[ ]`）。
- [ ] 看门狗：人为把 `timeoutMs` 压到 30000 并拖慢加载 → 只回一个 `TIMEOUT`（`responded` 生效），之后无第二个响应。
- [实测] 失败清理：早期失败（`WORLD_ALREADY_EXISTS`）不再断开玩家当前世界；后续 `world.create` 正常执行，未撞存档锁。
- [实测] `world.progress` 事件：`preparing → deleting → creating → awaiting_join → applying → (done)` 顺序符合预期（`done` 可能晚于响应到达，符合协议 §2.3 事件时序）；失败路径会推 `failed` 并伴随一个错误响应。
- [实测] MCP 侧：`MCB_LIVE=1 node smoke.mjs` 与 `MCB_LIVE=1 node integration.mjs` 全绿（含 `mc_world_create → mc_status → mc_world_delete` 真机链路）。

### 实测中发现并修复的实现缺陷（2026-11-17）

1. **`time_locked: null` 被 Gson 吃掉**：`new Gson()` 默认丢弃 `JsonNull` 成员（`JsonObject.add(key, null)` 直接删键），导致 `lock_time=null` 时响应缺字段。修复：`McbServer` 的 `GSON` 改为 `new GsonBuilder().serializeNulls().create()`，`McbWorldOps.finalApply` 显式 `JsonNull.INSTANCE`。
2. **失败响应先于单飞释放**：`fail()` 原来先 `onError.accept` 再清理/释放，导致紧随其后的请求在清理窗口内被误判 `WORLD_IN_USE`。修复：清理 → 释放 → 再响应（成功路径同样先释放再响应）。
3. **早期失败误退当前世界**：`cleanupAfterFailure` 原来无条件"回主菜单"，`WORLD_ALREADY_EXISTS` 这类未触碰世界的失败会把玩家从正在玩的世界踢出去。修复：新增 `worldTouched` 标记，只有真正 disconnect/建图过才清理。

### 已知风险 / 与协议的偏差（显式记录）

1. **`spawn` 不是请求参数复读**：取 `MinecraftServer#getRespawnData().pos()`。`void_platform` 由实现写入 (0,65,0)；超平坦/原版世界的 x/z 由 `setInitialSpawn` 的 `findSpawnPosition()` 决定（不保证正好是 0/8），协议只要求"实际可站出生点"。
2. **`void_platform` 生存模式的坠落窗口**：平台与出生点在集成服 ready 后的首个 `server.execute` 里写入（通常早于玩家 join），但若玩家已被 `PlayerSpawnFinder` 放到 `minY+1` 并开始坠落，`applying` 阶段会把 `y<60` 的玩家传送回平台并 `resetFallDistance()`。创造模式（默认）无此风险；**survival + 慢机器**窗口未实测。
3. **阻塞式创建期间客户端不响应**：`createFreshLevel` / `disconnect` 在客户端主线程阻塞数秒（原版加载屏，预期行为）；期间 TCP 线程仍可响应其他 op，但世界相关的 op 会等主线程。`world.progress` 的 `creating`→`awaiting_join` 之间可能因为主线程被占用而晚到。
4. **`clean_if_exists` 的旧世界删除在后台线程**，删除失败回 `INTERNAL`（不是 `WORLD_IN_USE`）；删旧与建新之间状态机每 tick 推进一步。
5. **`force` 语义**：只表示"允许先把正在运行的目标世界安全退出"，**不绕过删除安全红线**（§8.4）。
6. **状态机单飞 + 超时中止**：超时响应后状态机被标记中止（下次 tick 清理并回主菜单），不会产生第二个响应；但若超时恰好发生在 `createFreshLevel` 阻塞期间，中止要等该调用返回后才生效（原版加载流程不可中断）。
7. **`generate_structures=true` 的超平坦结构集**用原版 `STRONGHOLDS + VILLAGES`（`BuiltinStructureSets`）；数据包自定义结构集未接入。
8. **删除顺序**：先做安全判定再判"正在运行"（协议未规定顺序）；即"玩家世界正在运行且未 force"会回 `WORLD_DELETE_FORBIDDEN` 而不是 `WORLD_IN_USE`，更保守。


