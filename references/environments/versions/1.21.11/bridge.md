# MCB 调试桥实现要点（MC 1.21.11 · Fabric · Mojmap）

> 适用环境：MC 1.21.11，Fabric，Mojmap，JDK 21，Loom 1.17.21，Gradle 9.5.1。
> 验证方式：`[实测：编译探针]` = 组织工程 `debug-mcp/bridge-mod/` 全量编译 + remap 通过（Gradle 9.5.1 + loom 1.17.21 + loader 0.19.5，2026-11-15）；`[实测：javap]` = 用 loom 缓存的命名 jar（`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2.jar`）直接核签名，比解包 sources 更快。`[实测：运行时]` = 装进真实实例（HMCL 实例 `thatgfsj`，MC 1.21.11 + Loader 0.18.4 + JDK 21）后对活游戏的端到端调用，2026-11-15。`[实测：反编译源码]` = 直接阅读 1.21.11 Mojmap 反编译源码（`/tmp/mc1211all/net/minecraft/...`）并核对行号，2026-11-17。

> **运行时状态**：v0.5.1 之前的既有 op 已全部实测通过（见文末"运行时验证记录"）；v0.5.1 新增的 4 个 op、`command.run` 的 `side` 扩展与失焦保活守卫目前是**编译探针 / 源码核实，运行时待验证**（见"v0.5.1 增量 op 实现状态"与文末待补记录）。

## 协议实现（参考实现位置）

- 参考实现：`references/debug-mcp/bridge-mod/`（协议规范：`references/debug-mcp/protocol.md`）
- 构建验证：`gradle build` 全绿，产物 `mc-debug-bridge-0.2.0.jar`（remapJar 正常，2026-11-17 00:54；jar 内含 `McbClientOps.class` 与新 `McbScreenshot`/`GameRendererMixin`）。`[实测：编译探针]`；本节 API 结论以编译探针 + 反编译源码为准。

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

> 0.2.0（`mc-debug-bridge-0.2.0.jar`）于 2026-11-17 00:54 构建成功（remapJar 正常）；下表状态 = 编译探针 + 源码核实，**均未做真实游戏运行时验证**。

| 能力 / op | 实现点（参考实现） | 状态 |
|---|---|---|
| 失焦保活（启动关 `pauseOnLostFocus` + 渲染循环失焦守卫） | `McbMod.onInitializeClient()` + `mixin/GameRendererMixin`（`@Redirect` `Minecraft.pauseGame(Z)V`） | 编译探针 + 源码核实；**运行时待验证** |
| `command.run` 的 `side` 路由与 `returnValue`/`messages`/`side` 字段 | `McbServer` dispatch + `McbHandlers.commandRun`（server 路径）/ `ClientPacketListener.sendCommand`（client 路径） | 编译探针 + 源码核实；**运行时待验证** |
| `screen.current` | `McbClientOps.screenJson()`（客户端主线程） | 编译探针 + 源码核实；**运行时待验证** |
| `client.screenshot`（落盘版） | `McbClientOps.requestScreenshot(...)` + `GameRendererMixin` 帧末 `onFrameEnd()` + `McbScreenshot` | 编译探针 + 源码核实；**运行时待验证** |
| `client.pause_on_lost_focus` | `McbClientOps.pauseToggle(Boolean)`（写 `options` + `save()`） | 编译探针 + 源码核实；**运行时待验证** |
| `client.reload_resources` | `McbClientOps.reloadResources(callback)`（`reloadResourcePacks()` future + 30s 看门狗） | 编译探针 + 源码核实；**运行时待验证** |

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

