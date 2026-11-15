# MCB 调试桥实现要点（MC 1.21.11 · Fabric · Mojmap）

> 适用环境：MC 1.21.11，Fabric，Mojmap，JDK 21，Loom 1.17.21，Gradle 9.5.1。
> 验证方式：`[实测：编译探针]` = 组织工程 `debug-mcp/bridge-mod/` 全量编译 + remap 通过（Gradle 9.5.1 + loom 1.17.21 + loader 0.19.5，2026-11-15）；`[实测：javap]` = 用 loom 缓存的命名 jar（`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2.jar`）直接核签名，比解包 sources 更快。`[实测：运行时]` = 装进真实实例（HMCL 实例 `thatgfsj`，MC 1.21.11 + Loader 0.18.4 + JDK 21）后对活游戏的端到端调用，2026-11-15。

> **运行时状态：11 个 op 已全部实测通过**（见文末"运行时验证记录"）。

## 协议实现（参考实现位置）

- 参考实现：`references/debug-mcp/bridge-mod/`（协议规范：`references/debug-mcp/protocol.md`）
- 构建验证：`gradle build` 全绿，产物 `mc-debug-bridge-0.1.0.jar`（remapJar 正常）。`[实测：编译探针]`

## 编译探针核实过的 API（移植时直接对照）

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
