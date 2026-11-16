# PORTING — 怎么为其他环境写一个 MCB v1 桥接

> 本文是方法层：**怎么**把 `bridge-mod/`（Fabric 1.21.11 模板）移植到其他 Loader / 版本。
> 目标读者是 AI agent。开始前先按 SKILL.md Phase 0 输出目标环境卡，再按 §7 核实每个 API——本文列出的只是"历史上容易变的调用点"，不是可以照抄的清单。

## 移植六步

1. **传输层原样搬**（`McbServer` 的 socket / JSON / 线程模型）：纯 JDK + Gson，任何环境一字不改。要改的只有 Gson 的获取方式（MC 各版本都自带 Gson；NeoForge 同样自带）。
2. **日志捕获换锚点**：log4j2 Appender 方案在 Forge / NeoForge / Fabric 全线通用（MC 全家都跑 log4j2）。注意两点：
   - NeoForge 新版本线若换日志门面，先查该环境用什么，别凭记忆；
   - Appender 必须在游戏内容加载**之前** attach（入口最早时机），否则启动期错误抓不到。
3. **世界 API 逐个核实**（下表），每个调用点按 §7 走编译探针。
4. **线程跳转对准目标线程**：Fabric 模板用 `Minecraft.getInstance().execute()`（客户端主线程）；命令执行跳集成服务端线程。NeoForge 侧概念相同、入口类名不同（`Minecraft.getInstance()` 在 Mojmap 两侧同名）。**屏幕 / 失焦开关 / 资源重载也在客户端主线程**；截图分两段：像素读取在**渲染线程帧末**、PNG 编码与写盘在 IO 线程（`Util.ioPool()`）。
5. **截图按渲染管线核实**：`net.minecraft.client.Screenshot.takeScreenshot(RenderTarget, Consumer<NativeImage>)` 是 Mojmap 名（回调式，原生输出 PNG）；若目标环境管线不同（如 26.x 去混淆化时代改名），以反编译为准。`Screenshot.grab(...)` 虽然一步到位，但它自己异步写盘、无法在"像素读完后立即恢复 hideGui / 精确拿到绝对路径"上做时序控制——落盘截图 op 不要用它，用 `takeScreenshot` + 自写盘。
6. **失焦保活按目标版本重新核实**（v0.5.1）：原版自动暂停的调用点在 `render` 循环里（1.21.11 是 `GameRenderer.render(DeltaTracker, boolean)`），**不同版本的签名、判定条件、500ms 阈值都可能变**——必须重新反编译 grep `pauseGame` 定位，不能照抄本模板的注入点。

## 历史上容易变的调用点（核实清单）

| 模板调用点 | 所在文件 | 历史变动 |
|---|---|---|
| `net.minecraft.resources.Identifier` | McbHandlers | 1.21.11 Mojmap 才改名（旧版叫 `ResourceLocation`）；Yarn 全线叫 `net.minecraft.util.Identifier` |
| `BuiltInRegistries.*.getKey(x)` | McbHandlers | 注册表 API 1.21.2 前后 `get` 返回类型变过；`getKey` 稳定 |
| `blockEntity.saveWithFullMetadata(HolderLookup.Provider)` | McbHandlers | 1.20.5 起才有此签名（NBT 持有者化）；旧版是 `saveWithId`/`saveWithoutMetadata` |
| `player.getInventory().getItem(slot)` | McbHandlers | 1.21.9+ Inventory 重构过物品存储，方法名以反编译为准 |
| `Commands.performPrefixedCommand(CommandSourceStack, String)` | McbHandlers | Forge 线同名；Quilt 未核实 |
| `CommandSource` 四方法接口 | McbHandlers | `shouldInformAdmins` 的存在性按版本核实 |
| `Minecraft.getInstance().getMainRenderTarget()` | McbScreenshot | 26.x 去混淆化时代类名可能再变（见 `versions/1.21.11/vs-26.x-deobf.md`） |
| `GameType.getName()` | McbHandlers | 返回的是字符串如 `creative` |
| `Level#getDayTime / isRaining / isThundering` | McbHandlers | 长期稳定；维度 key：`level.dimension().identifier()`（1.21.11 已是 Identifier） |
| `Options.pauseOnLostFocus` | McbMod / McbClientOps | public boolean，默认 `true`；1.21.11 在 `Options` 里是普通字段（`Options.java:316`），`options.save()` 写回 options.txt。**字段名多版本稳定，但 Options 访问器/序列化方式随版本变**，以反编译为准 |
| `Options.hideGui` | McbClientOps / McbScreenshot | public boolean，F1 开关（1.21.11 `Options.java:717`）；抓帧时临时置 true 再恢复 |
| `GameRenderer.render(...)` 失焦分支里的 `minecraft.pauseGame(false)` | mixin/GameRendererMixin | **最容易变的一行**：方法签名（1.21.11 是 `render(DeltaTracker, boolean)`，旧版是 `render(float, long, boolean)`）、判定条件与 500ms 阈值都变过。1.21.11 在 `GameRenderer.java:465` 方法、`:466-472` 分支、`:470` 调用。**换版本必须重新反编译核实** |
| `Minecraft.pauseGame(boolean)` | 为什么不能作为守卫注入点 | ⚠️ **不是失焦专用**：ESC 也调用它（1.21.11 `KeyboardHandler.java:554`，参数是 F3 修饰键状态，普通 ESC 为 false）。不要全局取消；本模板也不在失焦调用点注入（见下方移植注意 2） |
| `Minecraft.screen` / `Screen.getTitle()` | McbClientOps | `screen` 是 public `@Nullable Screen` 字段（1.21.11 `Minecraft.java:360`）；`getTitle()` 返回 `Component`（`Screen.java:99`） |
| `Minecraft.isWindowActive()` | McbClientOps | 原版失焦判定谓词（1.21.11 `Minecraft.java:2617`），与 `pauseOnLostFocus` 判定同源 |
| `Minecraft.gameDirectory` | McbClientOps / McbScreenshot | `public final File`，即 `run/` 根（1.21.11 `Minecraft.java:303`）；截图目录是 `gameDirectory/screenshots/` |
| `Screenshot.takeScreenshot(RenderTarget, Consumer<NativeImage>)` | McbScreenshot | 1.21.11 存在且回调在渲染线程同步触发（`Screenshot.java:64`）；`grab(...)`（`:27/:31`）内部用它但把写盘丢给 `Util.ioPool()` |
| `Util.ioPool()` | McbScreenshot | 后台 IO executor（1.21.11 `Util.java:213`）；PNG 写盘不要占渲染线程 |
| `NativeImage.writeToFile(File)` | McbScreenshot | 1.21.11 `NativeImage.java:313`（Path 重载 `:342`）；`NativeImage` 没有 `asByteArray()` |
| `Minecraft.reloadResourcePacks()` | McbClientOps | 1.21.11 返回 `CompletableFuture<Void>`（`Minecraft.java:997`），客户端主线程调用；旧版本可能是同步/无 future，以反编译为准 |
| `ClientPacketListener.sendCommand(String)` | McbServer / McbHandlers | 参数**不带**前导 `/`；1.21.11 `ClientPacketListener.java:2670`（`sendChat` 在 `:2662`）。客户端命令通道只对已连接世界有效 |
| `MultiPlayerGameMode.getPlayerMode()` | McbClientOps | 1.21.11 `MultiPlayerGameMode.java:536` 返回 `GameType`；`getName()` 得 `creative` 等字符串 |

## 失焦保活移植注意（v0.5.1）

自动化环境里窗口失焦是常态，原版 500ms 后自动暂停会让世界冻结、op 超时、截图全是 PauseScreen。移植时按下面的优先级做，**不要**直接照搬"取消 pauseGame"：

1. **首选上游开关**：启动时把 `Options.pauseOnLostFocus` 置 `false` 并 `save()`（本模板 `McbMod` 的做法）。只依赖 Options 字段，几乎无跨版本/跨 Mod 冲突。务必 try/catch：开发工具不许因初始化失败炸客户端。**注意初始化时机**：Fabric 的 client entrypoint 在 `Minecraft.<init>` 内、`this.options = new Options(...)` 之前执行（fabric-loader 0.19.5 `EntrypointPatch.java:549-552` 在 `Thread.currentThread()` 前注入；1.21.11 `Minecraft.java:438/439`），所以入口处 `options` 可能为 null——写成"若 `options` 已就绪就立即设置，否则 `Minecraft.execute()` 排到首个 tick 重试（带日志与重试上限）"，不要把某个版本的构造器时序当成所有 Loader/版本都成立。
2. **运行期校正（本模板 0.2.1 起）**：不复用调用点注入，而是每渲染帧检查一次 `Options.pauseOnLostFocus`，guard 开启时把被外部改回 `true` 的值掰回 `false`（挂在已有的帧末 `@Inject` 上）。**不要用 `@Redirect` 拦那个调用点**：`@Redirect.at()` 的注解编码在 sponge-mixin 0.17.0（单值 `At`）与 0.17.4（`At[]`）之间不同——用新版编译、旧 Loader 运行会在 Mixin 变换阶段抛 `ClassCastException: ArrayList cannot be cast to AnnotationNode`，启动即崩（本仓库 0.2.0 真实事故，见 `pitfalls.md` E5）。`@Inject.at()` 两版一致，可安全使用。
3. **禁止**：全局取消 / `@Overwrite` `Minecraft.pauseGame(boolean)`。ESC 也走这个入口（见核实清单），拦了它等于把 ESC 暂停菜单一起干掉。
4. **运行时可控**：暴露一个"失焦暂停开关"op（本模板 §3.17），让兼容性测试能临时恢复原版行为，不必重启游戏。
5. **换版本先反编译**：grep `pauseGame` 找到该版本的调用点（可能在 `GameRenderer` / `Minecraft` / `Window` 回调里），核对方法签名与判定条件；`javap` 校验字段/方法存在性。**不同版本的调用点不同是常态，照抄注入点必然 `NoSuchMethodError` 或静默失效。**

## Loader 差异要点

### Fabric（本模板）
- 入口：`fabric.mod.json` `entrypoints.client`，实现 `ClientModInitializer`。**不需要 Fabric API**。
- `environment: "client"` 声明纯客户端 mod。

### NeoForge
- 入口：`@Mod` 注解类 + `DistExecutor`/`FMLEnvironment.dist` 判客户端；事件总线拿 `ClientPlayerNetworkEvent` 等价时机。
- 元数据：`META-INF/neoforge.mods.toml`，依赖声明格式不同。
- 传输层、日志捕获、协议分发逻辑可以**原样复用**（都是 JDK + log4j2 + 原版类）。
- 已有对照经验：`../../environments/loaders/fabric-vs-neoforge-1.21.x.md`。

### 专用服务端（dedicated server）
- 只能实现协议的子集：`ping`/`status`/`block.get`/`block.scan`/`map.ascii`/`entity.list`/`log.*`/`command.run`（服务端命令源更顺）。
- `screenshot.take`、`client.screenshot`、`screen.current`、`client.pause_on_lost_focus`、`client.reload_resources` 必须按协议返回 `CLIENT_ONLY`；`player.info` 返回 `present:false`（玩家）。
- 失焦保活是客户端概念，专用服务端没有对应开关——不要为了"代码统一"在服务端写 `Options` 相关逻辑。
- 入口时机：监听 `ServerStarted` 事件再开端口。

## 完成后的沉淀义务

移植成功后，按 `references/README.md` 的"新增环境知识"流程：
1. 在目标版本卡目录新建 `bridge.md`，记录该环境的实现要点与验证方式（编译过 + 行为测过才算）；
2. 更新 `environments/versions/README.md` 覆盖表；
3. 把实现放进 `debug-mcp/bridge-mod-<loader>-<版本>/`（复制式 core：与模板 diff 即得差异清单——这正是 SKILL.md §8.8 推荐的多环境模式）。
