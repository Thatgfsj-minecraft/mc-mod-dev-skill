# mc-debug-bridge — 极简调试桥（Fabric 1.21.11 参考实现）

> 适用环境：MC 1.21.11 · Fabric · Mojmap · JDK 21 · Loom 1.17.21（组织已验证坐标，见 `../../../environments/versions/1.21.11/README.md`）
> 协议：[MCB v1](../protocol.md)（v0.5.1 起含 `screen.current` / `client.screenshot` / `client.pause_on_lost_focus` / `client.reload_resources` 与 `command.run` 的 `side`；v0.5.2 起含 §7 `watch.start/await/snapshot/list/stop` 五 op + `watch.triggered/watch.stopped` 两事件）
> 验证方式：`gradle build` 编译通过（编译探针）；运行时行为验证记录在版本卡 `bridge.md`（v0.5.1 / v0.5.2 新增能力的运行时状态以版本卡为准）。

这是 MCB v1 协议的**首个参考实现**。它存在的意义不是"装上就能用"（当然也能用），而是**作为模板**：任何 AI 拿着协议规范 + [PORTING.md](PORTING.md)，就应该能为其他 Loader / 其他版本写出等价物。

## 设计取舍

- **零第三方依赖**：连 Fabric API 都不依赖——只用 loader 自带 client entrypoint、原版类、MC 自带的 Gson 与 log4j2。移植时唯一的依赖问题就是"没有依赖问题"。
- **单侧 client**：单人（集成服务端）与多人（客户端命令通道）场景都覆盖；`command.run` 用 `side` 显式选择执行路径（§3.9），不再靠"按序降级"隐式猜。
- **失焦保活**：启动时强制关掉原版失焦自动暂停（`Options.pauseOnLostFocus`），并用 Mixin 只拦**渲染循环的失焦调用点**；ESC 暂停路径一根手指都不碰（见红线 5）。
- **代码即文档**：每个文件头注明对应的协议章节；每个"容易写错"的点（线程纪律、响应唯一性、缺口可见性、抓帧时序）都在注释里标了协议出处。

## 文件地图

| 文件 | 行数级（≈） | 对应协议章节 | 职责 |
|---|---|---|---|
| `McbMod.java` | 75 | §1 / v0.5.1 失焦保活 | 入口：装日志捕获、开 TCP 服务端、解析端口、启动 WatchEngine、**登记失焦保活**（`McbClientOps.scheduleFocusKeepAlive()`；首个 client tick 应用，见下） |
| `McbLogCapture.java` | 220 | §3.7 / §3.8 / §2.3(log) | log4j2 Appender + 环形缓冲 + 增量读 + 订阅广播 |
| `McbServer.java` | 898 | §1 / §2 / §3 / §7 分发 | TCP accept / NDJSON 读写 / 主线程跳转 / 响应唯一性 / 看门狗；`command.run` 的 `side` 路由；`watch.*` 参数校验与 owner token；`broadcastEvent` |
| `McbHandlers.java` | 740 | §3.2–§3.6 / §3.9 / §3.10 | 世界状态读取与**服务端路径**命令执行（全部假设在游戏主线程 / 服务端线程） |
| `WatchSnapshot.java` | 846 | §7.1 / §7.3 / §7.10 | 服务端主线程采样与 diff：方块/实体快照、区块保护、filter/type_filter 匹配、三种报告段 JSON |
| `WatchRules.java` | 896 | §7.2 | 规则树解析/强校验/逐 tick 状态机（5 基础 + any/all/sequence/repeat + 超时兜底） |
| `WatchSession.java` | 792 | §7.1 / §7.3 / §7.4 / §7.9 | 会话配置、t0、采样节奏、报告缓冲/await、auto_stop/保留期、final_report |
| `WatchEngine.java` | 482 | §7.4–§7.9 | 全局注册表、tick 泵、生命周期回收、事件广播 |
| `McbClientOps.java` | 390 | §3.15–§3.18 | **客户端状态 op**：`screen.current` JSON、暂停开关读写、`client.screenshot` 抓帧调度、`client.reload_resources`；以及失焦暂停守卫标志 `autoPauseGuard()` |
| `McbScreenshot.java` | 147 | §3.11 / §3.16 | PNG 编码：§3.11 内联 base64；§3.16 `captureToFile` 帧末抓帧 → IO 线程写盘（渲染线程只读像素） |
| `McbCamera.java` | 230 | §3.12 / §3.14 | 天眼自由相机：姿态/FOV 覆盖、UI 抑制、相机射线 `camera.look` |
| `mixin/CameraMixin.java` | 46 | §3.12 | `Camera.setup` TAIL 覆盖姿态 |
| `mixin/GameRendererMixin.java` | 120 | v0.5.1 失焦保活 / §3.12 / §3.16 | FOV 覆盖（`getFov` TAIL）、第一人称手屏蔽、**帧末抓帧注入 + 失焦开关每帧校正**（`onFrameEnd`；**不用 `Redirect`**，原因见红线 7） |
| `mixin/GuiMixin.java` | 58 | §3.12 | 天眼期间按元素屏蔽原版 HUD |

> 行数为量级（v0.5.2 watch 落地后），以 `wc -l` 为准；三个 Mixin 类都必须在 `mcb.mixins.json` 的 **`client` 数组**里（它们引用 `Options` / `Screen` / `Gui` 等客户端类，放 common 数组会让专用服务端 ClassNotFound）。

## 失焦保活（v0.5.1，为什么这样改）

自动化场景里游戏窗口经常**失去焦点**（远程串流、alt-tab、X11 注入）。原版在失焦 500ms 后自动暂停，世界冻结、所有 op 变慢或超时、截图全是 PauseScreen——这不是"偶发 bug"，而是自动化环境的常态。桥接端从两个层面解决：

1. **上游开关**：`McbMod.onInitializeClient()` 末尾调用 `McbClientOps.scheduleFocusKeepAlive()`，把 `options.pauseOnLostFocus` 置 `false` 并 `options.save()`（已是 false 则不写盘）；整段包在 try/catch 里——开发工具不许因初始化失败炸客户端。**为什么延迟到首个 client tick**：fabric-loader 0.19.5 的 `EntrypointPatch` 在 `>=1.19.4` 时把 `startClient` 钩子注入到 `Minecraft.<init>` 内、`Thread.currentThread()` 调用之前（源码 `patch/EntrypointPatch.java:549-552`；`Hooks.startClient` 在此调用 `ClientModInitializer.onInitializeClient`），而 1.21.11 `Minecraft.<init>` 里 `this.gameThread = Thread.currentThread()`（`Minecraft.java:438`）早于 `this.options = new Options(...)`（`:439`）——入口处 `options` 可能还是 null。实现用 `Minecraft.execute()` 排队 + 最多 20 tick 重试（带日志），首个 `runTick` 在任何一帧渲染前执行完。`[实测：反编译 fabric-loader 0.19.5 sources + 1.21.11 Minecraft.<init> 顺序]`
2. **运行期校正（不用 Mixin 拦调用点）**：`GameRendererMixin` 的帧末 `Inject` 每帧调用 `McbClientOps.enforceFocusKeepAlive()`：guard 开启期间若 `pauseOnLostFocus` 被改回 `true`（F3+P / 选项重载 / 其他 Mod），当帧掰回 `false`。为什么不用 `Redirect` 拦失焦调用点：`Redirect.at()` 的注解编码在 sponge-mixin 0.17.0（单值 `At`）与 0.17.4（`At[]`）之间不同，0.2.0 曾因此启动崩溃（`ClassCastException: ArrayList cannot be cast to AnnotationNode`），而 `Inject.at()` 两版都是 `At[]`——模板必须跨 Loader 版本可用，所以只用 `Inject`（详见 `pitfalls.md` E5、红线 7）。
3. **运行时可控**：`client.pause_on_lost_focus`（§3.17）可读写该开关并联动 guard——要做"原版会暂停"的兼容性测试时不必重启游戏。

**红线：不动 ESC 路径。** 1.21.11 的 `KeyboardHandler` 里普通 ESC 也调用 `pauseGame(false)`；用 Mixin 全局取消 `pauseGame(boolean)` 会让 ESC 暂停菜单一起消失（详见红线 5 与版本卡）。

## 新增 op（v0.5.1）与实现位置

| op | 协议 | 实现点 | 线程纪律 |
|---|---|---|---|
| `screen.current` | §3.15 | `McbClientOps.screenJson()`，经 `hop()` | 客户端主线程读 `Minecraft.screen` / `options` |
| `client.screenshot` | §3.16 | `McbClientOps.requestScreenshot(...)` + `GameRendererMixin` 帧末 `onFrameEnd()` + `McbScreenshot.captureToFile(...)` | 请求在客户端主线程**武装**（`hide_hud` 必须在该帧渲染前置位——帧末注入点在 HUD/Screen 绘制之后）；像素读取在**渲染线程帧末**；PNG 写盘在 `Util.ioPool()`；写盘完成才响应 |
| `client.pause_on_lost_focus` | §3.17 | `McbClientOps.pauseToggle(Boolean)` | 客户端主线程（写 `options` 并 `save()`） |
| `client.reload_resources` | §3.18 | `McbClientOps.reloadResources(callback)` | 客户端主线程发起；future 完成回调里响应；30s 看门狗 |
| `command.run`（修订） | §3.9 | `McbServer` 的 dispatch 分支 + `McbHandlers.commandRun` | `side=server` → 集成服线程；`side=client` → `Minecraft.execute` 里调 `ClientPacketListener.sendCommand` |

## watch 会话引擎（MCB v1 §7，0.3.0 新增）

> 协议：`protocol.md` §7.1–§7.13。参考实现只在**集成服务端**（单人世界）可用：watch 的采样、规则评估与报告生成必须是服务端权威（服务端有完整区块、实体 AI 与 NBT；客户端只有视距内实体）。无集成服务端（多人世界 / 主菜单）→ `WORLD_NOT_LOADED`，message 以 `watch sessions require an integrated server (server-authoritative sampling)` 说明。专用服务端实现不在本次范围。
>
> 动机：把"什么时候发、发什么粒度"从消费端搬到桥接端——先立基准快照 t0、上传规则，桥接端逐 tick 在服务端主线程比较，命中后一次回传高价值结论（t1）。一次会话 = 观测范围（包围盒+过滤）× 上传规则（§7.2）× 报告模式（§7.3）。

### 新增 op 与实现位置

| op | 协议 | 实现点 | 线程纪律 |
|---|---|---|---|
| `watch.start` | §7.1 | `McbServer` dispatch（IO 线程参数强校验）→ `WatchEngine.startWatch` 在服务端线程捕获 t0 | 参数校验在 IO 线程；采样在集成服线程；响应经 `responded` 去重 + 10s 看门狗 |
| `watch.await` | §7.4 | `WatchEngine.awaitWatch` → `WatchSession.awaitData` | 在 `LOG_WAIT_POOL` 线程等 session monitor，游戏线程不阻塞；`matched:false` 是正常分支 |
| `watch.snapshot` | §7.5 | `WatchEngine.snapshotWatch` → `WatchSession.buildSnapshotReport` | 服务端线程采样；`seq` 取当前最新值不 +1、不入缓冲；已释放基准的会话 → `WATCH_NOT_FOUND` |
| `watch.list` | §7.6 | `WatchEngine.listWatches` | IO 线程读元数据（无世界访问），只列活跃会话 |
| `watch.stop` | §7.7 | `WatchEngine.stopWatch` | 纯会话状态（释放快照 / 取 `final_report`），IO 线程即可；非 owner → `BAD_PARAMS`（message 含 `not the owner`） |

### 四个新文件

| 文件 | 行数 | 职责 |
|---|---|---|
| `WatchSnapshot.java` | 846 | §7 采样与 diff：`Sample` 用并行 `long[] keys` + `BlockState[]`（单会话 ≤32768 格，无逐方块对象）；未加载区块整列跳过并记 `truncated_chunks`；实体 UUID 主键 + runtimeId 辅助判定 `out_of_bounds`；`summary_diff` / `timeline_events` / `snapshot` 三种报告段 JSON |
| `WatchRules.java` | 896 | §7.2 规则树：5 基础规则 + `any/all/sequence/repeat`；深度 ≤4、节点 ≤16、序列化 ≤16 KiB、参数范围/正则/事件源强校验（`RULE_INVALID`）；逐 tick 状态机（latched/simultaneous、sequence 激活、repeat 轮次、超时兜底） |
| `WatchSession.java` | 792 | 会话配置与参数校验（IO 线程）；t0 基准；逐 tick/按需采样节奏；报告缓冲（≤64、seq、overflowed）；`watch.await` wait/notify；auto_stop/保留期；`watch.list` 元数据；`final_report` |
| `WatchEngine.java` | 482 | 全局注册表（活跃 ≤8；关闭但保留报告的会话仍可 await）、daemon tick 泵（轮询 `getSingleplayerServer().getTickCount()` → `server.execute(...)`）、维度卸载/服务器切换/连接断开 60s/interval·repeat 空闲 300s 回收、`watch.triggered`/`watch.stopped` best-effort 广播 |

### 线程纪律（实现要点）

1. **所有世界读取只在集成服线程**：tick 泵只在 `tickCount` 变化时 `server.execute(...)`；采样、规则评估、报告生成全在回调里。TCP 线程只做参数校验与会话注册表查找。
2. **不强制加载区块**：采样先按 (chunkX, chunkZ) 用 `Level.isLoaded(pos)` 判缓存（纯查询），未加载区块整列跳过并记入 `truncated_chunks`；条件规则读单方块前同样先 `isLoaded`。**不要**先 `getBlockState` 再判断——`Level.getBlockState` 内部会 `getChunk(...)`。
3. **`watch.await` 不占游戏线程**：`LOG_WAIT_POOL` 线程在 session monitor 上 `wait(remaining)`；服务端线程只在 append 报告后 `notifyAll()`；会话关闭立即唤醒并返回（不会白等满 `wait_ms`）。
4. **采样节奏**（§7.10.3）：`settle_quiet` / `timeline_events` 逐 tick 全量采样；`interval` 只在报告边界采样；纯时间/条件规则只在 t0 与触发时各采一次；`tracks.blocks/entities.enabled=false` 时对应轨道不采样。
5. **关闭会话不跨线程释放快照**：`watch.stop` / owner 断连 / 空闲回收只把会话标记 closed 并移入保留区（`closeForStop` 在同一 session 锁内取 `final_report`，避免"报告已产生但 final_report 漏掉"的竞态）；`releaseSnapshot()` 由 `WatchEngine` 投递回集成服线程执行，只有服务端已不存在时才直接同步释放（所有读取路径都对 null 容错且先看 volatile `closed`）。锁顺序恒为 `engine LOCK → session 锁`，session 锁内不获取 LOCK。
6. **事件背压**：`watch.triggered` / `watch.stopped` 走 `McbServer.broadcastEvent` → 每连接一次非阻塞 `offer`；满则丢并计入 `status.droppedEvents`（与 log 事件共用 `McbLogCapture.onEventDropped()`）。可靠渠道永远是 `watch.await` 的 seq 游标。

### 协议语义落点（易错项）

- `watch_id` 全局唯一（含 60s 保留期内已关闭会话）；正则 `^[A-Za-z0-9_-]{1,64}$`、不含 `.`；重复 → `BAD_PARAMS`。
- 区域体积三轴乘积 >32768 → `TOO_LARGE`；活跃会话 >8 → `TOO_LARGE` 且 message 含 `watch session limit reached`。
- auto_stop 默认：`after*`/`settle_quiet`/`on_condition`/`event`/`any`/`all`/`sequence` = true；`interval`/`repeat` = false；`repeat` 轮次用尽或 `until` 命中 → 结束会话（末份报告即 `final_report`）。
- 报告：`summary_diff` 的 `transitions` 按计数降序取前 `detail_limit`；`timeline_events` 的 `counts` 始终完整、`events` 截断置 `truncated/truncated_count`；`snapshot` 的 `blocks/entities` 受 `detail_limit` 限制。单行报告由这些上限保证 <1 MiB（方块实体 SNBT 额外 512 字符截断）。
- `watch.await` 缓冲 64 份；**逐份游标**：`matched:true` 时 `next_seq` = 本次返回报告的 seq（interval/repeat 在两次 await 之间产生的多份报告不会被跳过），`matched:false` 时 = 缓冲区最新 seq（无匹配也前进，避免空转）；缺口用 `overflowed:true` 暴露；已关闭会话立即返回 `matched:false`。
- `watch.snapshot` 的 `seq` 为当前最新值但不 +1、不入缓冲；`trigger_reason="snapshot"`。

### 已知限制 / 实现注记

- **只在集成服务端**：多人世界无服务端权威数据，返回 `WORLD_NOT_LOADED`；专用服务端需另做实现（协议 §7 不变）。
- **`out_of_bounds` vs `removed` 的运行时 id 辅助**：`ServerLevel.getEntities()` 是 protected，只能按 §7.10.4 允许的 `getEntity(int)` 辅助判断；运行时 id 可能复用，**UUID 仍是唯一主键**，判定时额外校验 `live.getUUID().equals(old.uuid)`，仍存在极端复用误判可能（记录于此）。
- **实体截断的边界抖动**：`max_entity_count` 按到 origin 距离（无 origin 按 x→y→z）取前 N；距离边界上的实体集合可能跨 tick 抖动，报告可能出现个别 entered/removed 噪声。
- **实体条件只看到被跟踪实体**（受 `tracks.entities.type_filter` 与 `enabled` 约束）；实体类型 `#tag` 在 condition 里只能按 id 兜底，建议用 id/glob。
- **属性变化计入方块变化**：BlockState 变化（如 `lit` 翻转）算一次 changed，`transitions` 可能出现 `minecraft:foo -> minecraft:foo` 同 id 键（from/to 是 state id 投影）。
- `watch.start` 被看门狗超时后若服务端回调迟到，会话可能已注册但客户端不知道 id；此类孤儿会话由 auto_stop / 空闲回收 / 生命周期清理消化（已知窗口）。

## 构建与安装

```bash
cd references/debug-mcp/bridge-mod
./gradlew build          # 需要 Gradle 9.5.1（见版本卡）；wrapper 未入库，可 `gradle wrapper` 生成
# 产物: build/libs/mc-debug-bridge-0.3.0.jar
```

把 jar 放进游戏实例的 `mods/` 文件夹即可。游戏启动后桥接监听 `127.0.0.1:25585`；改端口用 JVM 参数 `-Dmcb.port=NNNN`。

联调可用任何 MCP 客户端 + 本目录同级 `mcp-server/`（协议参考消费端）。

## 红线（改这个模板前必读）

1. **只监听 127.0.0.1**——`McbServer.start` 里的 bind 地址不许改，这个服务端没有鉴权。
2. **响应唯一性**——`command.run` / `client.screenshot` / `client.reload_resources` 都有超时看门狗或异步回调，靠 `responded` 集合保证一个请求只回一个响应（协议 §2.2）。新增长耗时 op 时必须复用这个机制（超时/异常路径也要恰好一个响应）。
3. **主线程纪律**——任何 `level.getBlockState` 之类调用只允许出现在 `Minecraft.execute()` 里。在 TCP 线程读世界 = 随机崩溃。
4. **缺口可见性**——日志环形缓冲丢条目时必须置 `overflowed`，不许静默。
5. **ESC 路径不可拦**——`pauseGame(boolean)` **不是失焦专用入口**：1.21.11 里普通 ESC 也调用 `pauseGame(false)`（`KeyboardHandler:554`，参数是 F3 修饰键状态）。失焦暂停的正确做法是维护上游开关 `Options.pauseOnLostFocus`（启动关 + 运行期每帧校正）；粗暴取消 `pauseGame` 会让 ESC 暂停菜单一起失效。`[实测：反编译 1.21.11]`
6. **截图线程纪律**——像素/帧缓冲只能在**渲染线程帧末**读；PNG 编码与写盘放 `Util.ioPool()`；`hide_hud` 的临时 `options.hideGui` 必须在**同一次像素读取回调里**恢复（否则漏改全局状态，或抓到 HUD 已恢复的帧）。
7. **watch 采样必须服务端线程**——`WatchSnapshot.capture` / 规则评估 / 报告生成只允许出现在 `server.execute(...)` 回调；IO 线程读世界 = 随机崩溃且可能强制加载区块。
8. **禁止强制加载区块**——采样/条件读方块前必须先 `Level.isLoaded(pos)`；`Level.getBlockState` 会走 `getChunk(...)`。
9. **`watch.await` / 事件推送不得阻塞游戏线程**——await 只在 `LOG_WAIT_POOL` 线程等 session monitor；事件只做非阻塞 `offer`，满则丢并计入 `droppedEvents`。
10. **Mixin 选择 + 注解版本陷阱**——本参考实现只用原版 Mixin 的 `Inject`（`Inject.at()` 在 sponge-mixin 0.17.0 / 0.17.4 都是 `At[]`，跨 Loader 版本稳定）；**不要用 `Redirect`**：它的 `at()` 在 0.17.0 是单值、0.17.4 是 `At[]`，编译端与运行端 Mixin 版本不一致时注解编码对不上，MixinExtras `preApply` 会抛 `ClassCastException: ArrayList cannot be cast to AnnotationNode` 导致启动崩溃（0.2.0 真实事故）。正式模组优先 MixinExtras 的 `WrapOperation` / `ModifyExpressionValue`（并且同样要核对运行时 Mixin 版本）。引用 `Options` / `Screen` 的 Mixin 必须放 `mcb.mixins.json` 的 `client` 数组。
