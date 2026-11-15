# PORTING — 怎么为其他环境写一个 MCB v1 桥接

> 本文是方法层：**怎么**把 `bridge-mod/`（Fabric 1.21.11 模板）移植到其他 Loader / 版本。
> 目标读者是 AI agent。开始前先按 SKILL.md Phase 0 输出目标环境卡，再按 §7 核实每个 API——本文列出的只是"历史上容易变的调用点"，不是可以照抄的清单。

## 移植五步

1. **传输层原样搬**（`McbServer` 的 socket / JSON / 线程模型）：纯 JDK + Gson，任何环境一字不改。要改的只有 Gson 的获取方式（MC 各版本都自带 Gson；NeoForge 同样自带）。
2. **日志捕获换锚点**：log4j2 Appender 方案在 Forge / NeoForge / Fabric 全线通用（MC 全家都跑 log4j2）。注意两点：
   - NeoForge 新版本线若换日志门面，先查该环境用什么，别凭记忆；
   - Appender 必须在游戏内容加载**之前** attach（入口最早时机），否则启动期错误抓不到。
3. **世界 API 逐个核实**（下表），每个调用点按 §7 走编译探针。
4. **线程跳转对准目标线程**：Fabric 模板用 `Minecraft.getInstance().execute()`（客户端主线程）；命令执行跳集成服务端线程。NeoForge 侧概念相同、入口类名不同（`Minecraft.getInstance()` 在 Mojmap 两侧同名）。
5. **截图按渲染管线核实**：`net.minecraft.client.Screenshot.takeScreenshot(RenderTarget, Consumer<NativeImage>)` 是 Mojmap 名（回调式，原生输出 PNG）；若目标环境管线不同（如 26.x 去混淆化时代改名），以反编译为准。

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
| `Screenshot.takeScreenshot(RenderTarget, Consumer<NativeImage>)` | McbScreenshot | Mojmap 名，回调式；`NativeImage` **没有** `asByteArray()`（历史上有过类似方法，勿凭记忆），PNG 落盘用 `writeToFile(path, false)` 再读回 |
| `GameType.getName()` | McbHandlers | 返回的是字符串如 `creative` |
| `Level#getDayTime / isRaining / isThundering` | McbHandlers | 长期稳定；维度 key：`level.dimension().location()` |

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
- `screenshot.take` 与 `player.info` 必须按协议返回 `CLIENT_ONLY`（截图）/ `present:false`（玩家）。
- 入口时机：监听 `ServerStarted` 事件再开端口。

## 完成后的沉淀义务

移植成功后，按 `references/README.md` 的"新增环境知识"流程：
1. 在目标版本卡目录新建 `bridge.md`，记录该环境的实现要点与验证方式（编译过 + 行为测过才算）；
2. 更新 `environments/versions/README.md` 覆盖表；
3. 把实现放进 `debug-mcp/bridge-mod-<loader>-<版本>/`（复制式 core：与模板 diff 即得差异清单——这正是 SKILL.md §8.8 推荐的多环境模式）。
