# 实时调试基建（MCB）— 让 AI 直接看游戏、看日志、操作世界

> 适用环境：环境无关（协议层）。各环境桥接实现的可用性与要点见 `environments/versions/<版本>/bridge.md`（没有对应文件 = 该环境尚无已验证实现，按 §13 处理）。
> 验证方式：本文是方法层；协议契约见 `debug-mcp/protocol.md`，Fabric 1.21.11 参考实现在 `debug-mcp/bridge-mod/`。

## 1. 这是什么

MCB（Minecraft Console Bridge）是组织沉淀的**实时调试三件套**：

```
游戏 JVM 内的桥接 Mod（每环境一个，照协议写）
    ↕  MCB v1：TCP + NDJSON，127.0.0.1:25585
MCP Server（本参考实现，环境无关）
    ↕  MCP 协议
AI agent（你）
```

- 协议规范：`debug-mcp/protocol.md` —— 契约层，桥接端与消费端各自独立实现它
- 桥接 Mod 模板：`debug-mcp/bridge-mod/` —— Fabric 1.21.11 参考实现 + [PORTING.md](debug-mcp/bridge-mod/PORTING.md) 移植指南
- MCP Server：`debug-mcp/mcp-server/` —— TypeScript 参考消费端，工具与 op 一一对应

设计原则：**不针对任何具体模组**。桥接只做六件事——读世界、抓日志、跑命令、截图、查询/控制客户端状态（屏幕/焦点/资源重载）、规则驱动观察（watch 会话：t0 基准 → 规则触发 → t1 报告）；一切编解码/游标/重连逻辑都在消费端。

## 2. 什么时候用

| 场景 | 用法 |
|---|---|
| 开发循环里的行为验证（SKILL.md §10 的增强） | `mc_command` 搭场景 → `mc_wait_log` 等结果日志 → `mc_logs` 拉增量 → `mc_map` / `mc_scan_blocks` 看世界变化 |
| 有观察窗口的实验（爆炸半径/连锁反应/实体伤害） | `mc_watch_start` 声明规则（after_seconds / settle_quiet / on_condition / interval）→ 一次 `mc_watch_await` 拿回 t0→t1 的 summary_diff / timeline_events / snapshot 报告；不要手动 sleep + 反复 scan |
| "编译过了但表现不对"（SKILL.md §11 的第一现场） | `mc_logs(levelMin=ERROR)` 直接看堆栈；`mc_wait_log` 抓异常关键字 |
| 让 AI 建立空间感 | `mc_map` 俯视图 + legend；大区域先 `mc_scan_blocks` 过滤关键方块，再 `mc_block_get` 精查 |
| 开发循环里的分级热迭代（`fast-iteration.md`） | 数据包 `mc_command({command:"reload"})`；资源 `mc_reload_resources` → `mc_logs` 搜缺失 → `mc_client_screenshot` 验收 |
| 游戏"卡住" / 画面被挡 | `mc_screen_current` 看 `screen_class` / `window_focused` / `pause_on_lost_focus`，不花 token 读图 |
| GUI / 视觉类模组 | `mc_screenshot` 内联看画面；要留档/前后对比用 `mc_client_screenshot` 落盘 |
| 模组自检（§10 的启动自检） | 桥接从进程启动就抓日志，游戏启动期的错误也能回看 |
| 多人世界 / 无集成服务端的客户端命令 | `mc_command({command, side:"client"})`（无回显，`returnValue:null`） |

**边界**：MCB 是单人开发工具（客户端桥接 + 集成服务端命令）。E2E 断言基线仍然是专用服务器 + 服务端权威断言（`e2e-testing.md`）——MCB 补的是"实时看 + 实时操作"，不是替代回归测试。

## 3. 接入步骤（一次性）

1. 构建 `debug-mcp/bridge-mod/`（按版本卡 `bridge.md` 的已验证坐标），jar 放进开发实例 `mods/`。
2. 构建 `debug-mcp/mcp-server/`，在你的 MCP 客户端注册（README 有配置样例）。
3. 启动游戏。**bridge-mod 0.4.0+ 首次进入主页面会询问是否开启开发者模式**：点【是】后桥接监听 `127.0.0.1:25585`（改端口：JVM 参数 `-Dmcb.port=`）；点【否】保持纯原版。自动化启动必须带 `-Dmcb.devMode=true` 跳过确认屏（`-Dmcb.devMode=false` 则静默关闭）。
4. 连通性自检：调 `mc_status`，能返回即链路正常；游戏未启动时工具返回 `BRIDGE_UNREACHABLE`，属预期。
5. 若 `mc_status` 一直 `BRIDGE_UNREACHABLE`：先确认确认屏已点【是】或已带 `-Dmcb.devMode=true`；0.4.0+ 默认不监听端口，这不是链路故障。

## 4. 使用纪律

1. **先 `mc_status` 再动手**——世界没加载时多数 op 会回 `WORLD_NOT_LOADED`，先建/进世界。
2. **等日志用 `mc_wait_log`，不要盲轮询**——长轮询是协议一等公民，轮询既慢又可能撞游标缺口。
3. **游标纪律**——`mc_logs` 的 `afterId` 自己持有；游标只在同一次游戏会话内有意义：**游戏重启（收到新 hello）后游标归零，从 `afterId=0` 重看**。看到 `overflowed: true` 说明有缺口（协议 §3.7 有精确定义），回 `afterId=0` 重看或直接查崩溃报告。
4. **空间感知三段式**——`mc_map`（看布局）→ `mc_scan_blocks`（定位关键方块）→ `mc_block_get`（精查状态/NBT）。直接对大区域硬扫是浪费上下文。
5. **命令失败要读回执**——`mc_command` 返回 `resultText`（命令反馈原文），setblock 打错坐标、give 配错 id 都会在这里显形，不要假设成功。
6. **修改场景用一次性世界**——命令是四级权限，`kill @e` / 大范围 fill 都执行得出去；在专用测试存档里玩（与 `e2e-testing.md` 的世界纪律一致）。
7. **能"看"就别"动"**——角色不会飞（重力会把他拽下去），移动玩家前先探目标下方有没有可站方块，没有就垫玻璃；`mc_move_player` 已把这条纪律硬编码进桥接端，优先用工具而不是手写命令。只是想看某个位置/方块的外观时，用天眼 `mc_camera_eye`——玩家根本不动，也就没有坠落风险。
8. **天眼的三条工作流**：
   - `mc_camera_look`（"镜头在看哪个方块"）→ 比截图便宜得多，返回命中方块的 id/属性/方块实体 NBT，**优先用它**；
   - `mc_camera_eye`（一次性出图）→ 需要看**外观/渲染效果**时才用；
   - `mc_camera_set` + `mc_screenshot`（持久模式多次取景）→ 需要从多个角度反复观察同一目标时用。
   - ✅ mod 信息栏（Jade）在 `displayMode: LITE` + `perspectiveMode: CAMERA` 下**会跟随虚拟相机**，高亮框套在准星指的方块上——但要结构化数据（id/属性/NBT）还是用 `mc_camera_look` 更省事。
9. **天眼拍到虚空就是区块没加载**——响应里的 `chunks.centerLoaded` / `loadedNearby` 会如实报；`centerLoaded=false` 时先 `mc_command` 跑 `forceload add <cx> <cz>` 再拍。
10. **拍图前先问屏幕**——`mc_client_screenshot` / `mc_screenshot` 之前先 `mc_screen_current`：`screen_class` 非 null（尤其 PauseScreen）说明画面被 GUI 挡住，`hide_hud` **不会**隐藏已经打开的 Screen。
11. **验收截图用落盘版**——`mc_screenshot` 内联 base64 适合"看一眼"；`mc_client_screenshot` 写到 `<gameDir>/screenshots/` 并返回绝对路径与字节数，适合跨步骤前后对比、事后留档。
12. **资源类改动走 L2 闭环**——`mc_reload_resources` 返回 `{reloaded,duration_ms}` 后**必须** `mc_logs` 搜缺失/加载错误，再截图；不要用按键注入模拟 F3+T（窗口焦点不可靠）。
13. **`command.run` 的 `side` 要显式**——集成服断言用 `side:"server"`（可拿 `returnValue`/反馈）；多人世界客户端命令用 `side:"client"`（无回显，`resultText:""`、`returnValue:null`）；不要假设 `auto` 一定走服务端。
14. **有观察窗口的实验用 watch 会话，不要手动 sleep 轮询**——`mc_watch_start` 先拍 t0 基准并声明 `upload_rule`，命中后一次 `mc_watch_await` 拿回结论；`next_seq` **逐份前进**（interval/repeat 的多份报告不会漏），`matched:false` 是正常超时；`auto_stop` 后报告保留 60s，过期才 `WATCH_NOT_FOUND`。`mc_watch_stop` 只有创建会话的连接能调；参考 Fabric 客户端桥接仅集成服可用（多人世界回 `WORLD_NOT_LOADED`）。规则/报告模式/字段见协议 §7。
15. **开发者模式是启动开关**——0.4.0+ 默认不占端口、不改失焦暂停，必须先点确认屏【是】或带 `-Dmcb.devMode=true`；自动化漏传该参数的表现是“游戏正常但 MCP 一直 BRIDGE_UNREACHABLE”，不是桥接坏了。配置/优先级见 bridge-mod README 与 `e2e-testing.md`。
16. **搭场景前先建测试世界（v0.5.4）**——`mc_world_create` 一次拉起干净世界（默认创造 / 关结构 / 关自然刷怪 / normal 难度 / 锁正午晴天），之后再用 `mc_command` 改世界；**不要默认进玩家的生存存档**，也不要在一轮实验的残留世界里接着做。固定存档名 + `clean_if_exists:true` 是 fast-iteration 纪律，测试结束 `mc_world_delete`（完整闭环见 §6.9、契约见协议 §8）。
17. **建图是异步的，响应回来才算数**——`world.create` 在"进世界 + 应用 gamerule"完成后才回响应（可能 10s 级，期间只有 `world.progress` 事件）；不要在响应返回前发后续命令。`BAD_OP` = 桥接 Mod < 0.5.0（协议 v0.5.4 才新增 `world.*` op，回退手工进世界）；`CLIENT_ONLY` = 专用服桥接不支持建图/删图；`WORLD_IN_USE` = 另一个建/删正在飞，不要并发。

## 5. 为新环境写桥接（扩展流程）

1. 读 `debug-mcp/protocol.md`（契约）+ `debug-mcp/bridge-mod/PORTING.md`（移植五步 + 历史易变调用点清单）。
2. 按 SKILL.md §7 对清单逐个核实 API，实现桥接（传输层基本原样搬，世界 API 按目标映射重写）。
3. 验证：编译通过 + 行为测试（至少覆盖 `ping`/`status`/`block.get`/`log.history`/`command.run` 五条主路径）。
4. 按 `references/README.md` 沉淀：目标版本卡目录新建 `bridge.md`（写明验证方式），更新覆盖表，实现放进 `debug-mcp/bridge-mod-<loader>-<版本>/`。
5. watch（§7）需要**服务端 tick 泵**（集成服 `server.execute` / 专用服 `ServerTickEvents.END_SERVER_TICK`）与区块 `isLoaded` 保护；桥接端只能在服务端主线程采样/评估/生成报告。客户端桥接只在集成服可用，专用服务端实现更自然。

## 6. 实战剧本（怎么把工具串成调试闭环）

以下是真实调试中验证有效的调用序列。工具名以参考实现（技能包 v0.5.3，bridge-mod 0.4.0，共 24 个 `mc_*` 工具，与协议 op 一一对应）为准，其他实现按协议 op 对应。

### 6.1 搭场景 → 看结果

```
mc_command("setblock x y z minecraft:glass")   # 造环境（服务端权威，避免幽灵方块）
mc_scan_blocks({min,max,filter})               # 确认方块真的到位
mc_camera_look({x,y,z, lookAt:[tx,ty,tz]})     # 镜头对准它，拿到 id/属性/NBT
mc_camera_eye({x,y,z, yaw, pitch})             # 需要看外观/渲染时才截图
```

**要点**：`camera.look` 比截图便宜得多，能回答"这是什么东西、状态如何"；只有当问题涉及**渲染外观**（贴图、颜色、UI 叠加）时才值得花 token 截图。

### 6.2 角色交互类任务

```
mc_move_player({x,y,z})                        # 探地 + 垫玻璃 + 传送，全部硬编码
mc_command("...")                              # 在安全位置执行交互
mc_camera_eye({...})                           # 旁观效果，不动角色
```

**要点**：需要"放置/破坏/交互"才移动角色；只为了"看"就用天眼。移动后如果发现角色状态被污染（半血、坠落中），先 `mc_status` 查 `player` 字段。

### 6.3 排查"渲染不对"类问题

```
mc_camera_look({x,y,z, lookAt:[tx,ty,tz]})     # 1. 先确认目标方块的状态（数据层）
mc_camera_eye({x,y,z, yaw, pitch})              # 2. 再看画面（渲染层）
```

**要点**：数据层与渲染层分开看——如果 `camera.look` 返回的状态是对的但画面不对，那是渲染/客户端同步问题；反之则是方块实体逻辑问题。这个二分法能省掉大量盲目重试。

### 6.4 观察自动化流程（漏斗/熔炉/坩埚循环）

```
mc_camera_set({x,y,z, yaw, pitch, fov})        # 持久模式，每帧覆盖姿态
mc_screenshot({})                              # 多次取景，不同角度/距离
mc_camera_clear({})                            # 用完恢复
```

**要点**：持久模式比反复 `camera.eye` 更省——一次布好机位，连拍多张对比。`camera.clear` 必须调用，否则视角一直是虚拟相机（`status.cameraMode` 会显示 `persistent`，可用来确认状态）。

### 6.5 L2 资源热重载闭环（改贴图 / 模型 / 语言）

```text
mc_reload_resources()                                # 1. 重载资源包（程序化 F3+T），等 duration_ms 返回
mc_logs({ levelMin: "WARN" })                        # 2. 搜加载错误：Failed to load model / Missing textures in model ...
mc_screen_current()                                  # 3. 确认没有启动屏 / PauseScreen 挡画面
mc_client_screenshot({ filename: "l2-after.png" })   # 4. 落盘验收：紫黑格 / 缺贴图是否消失
```

**要点**：

- 重载只说明"资源管理器流程结束"，**不保证**每个模型/贴图都成功——第 2 步不能省（1.21.11 的真实日志文本见 `fast-iteration.md` §6 规则 3）。
- 资源文件要先同步到运行 classpath（`runClient` 读 `build/resources/main`）；在 IDE 外改文件时先 `./gradlew processResources`，否则重载看不到变化。
- 重载期间客户端会卡一下（资源包解析 + 纹理重建）；30s 看门狗超时说明资源包大 / 着色器编译慢，不代表失败，但要如实记录并继续查日志。

### 6.6 指令注入 + 画面查询 + 截图验收（完整闭环）

```text
mc_screen_current()                                   # 1. 基线：screen_class=null、window_focused=true、pause_on_lost_focus=false
mc_command({ command: "time set noon", side: "server" }) # 2. 注入：显式 side=server，看 success / returnValue / resultText
mc_wait_log({ pattern: "..." })                       # 3. （可选）等服务端副作用日志，别用 sleep 盲等
mc_screen_current()                                   # 4. 复查：确认没有 Screen 挡住画面（加载屏 / PauseScreen）
mc_client_screenshot({ filename: "verify-after.png" }) # 5. 落盘截图：返回绝对 path + 图像，按文件名留档对比
mc_logs({ levelMin: "ERROR" })                        # 6. 画面异常时拉堆栈，分清渲染层还是逻辑层
```

**要点**：

- 第 2 步的 `resultText` / `returnValue` 是"命令真的执行了"的第一手证据；`success:false` 先读 `resultText` 的错误文本，不要假定坐标 / id 写对了。
- 第 3 步用 `mc_wait_log` 长轮询（协议一等公民），不要 `mc_logs` 盲轮询（游标可能漏）；游标规则见 §4 第 3 条。
- 第 5 步用固定 `filename` 命名（如 `before.png` / `after.png`），跨步骤引用同一个文件；`mc_client_screenshot` 的 `path` 在 `<gameDir>/screenshots/`（Loom 默认 `run/screenshots/`）。
- 需要"干净画面"时加 `hide_hud: true`——只影响该帧，**不隐藏已经打开的 Screen**；先 `mc_screen_current` 再拍。

**截图是 PauseScreen 时怎么判断**：看 `mc_screen_current` 的字段组合，不要猜图。

| 字段组合 | 含义 | 对策 |
|---|---|---|
| `screen_class: "net.minecraft.client.gui.screens.PauseScreen"`，`screen_title` 是暂停菜单标题 | 游戏暂停了，画面被暂停菜单挡住 | 先看 `pause_on_lost_focus`：若为 `true`，说明原版失焦暂停生效（守卫没跑 / 被兼容性测试打开）——`mc_pause_on_lost_focus({enabled:false})` 只能**防止再次**弹出，**已弹出的 PauseScreen 没有关屏 op**，需人工关掉或重启客户端 |
| `window_focused:false` + `pause_on_lost_focus:false` + `screen_class:null` | 窗口失焦但游戏仍在跑（桥接守卫生效） | 画面可用，不必处理；别误判成"卡死" |
| `window_focused:false` + `pause_on_lost_focus:true` + `screen_class:"…PauseScreen"` | 原版失焦自动暂停（守卫未生效 / 被关） | `mc_pause_on_lost_focus({enabled:false})` 恢复守卫；确认桥接 0.2.1+ 启动日志有 `pauseOnLostFocus disabled` |
| `screen_class` 是其它非 null 值（加载屏 / 容器界面等） | 世界或资源仍在加载，或另一个 GUI 挡着 | 等日志 / 重试截图；不要在加载屏上验收渲染 |

> 结论：**防 PauseScreen 要在它出现之前**（`run/options.txt` 预置 + 桥接启动强制关，见 `e2e-testing.md`「客户端 run 目录预置」），出现之后只能人工处理。

### 6.7 规则驱动观察（watch）——爆炸 / 连锁反应 / 实体状态

有观察窗口的实验（爆炸、连锁破坏、机器流水线、实体 AI/伤害）用 watch 会话，一次拿结论：

```text
mc_watch_start({
  watch_id: "explosion_test_01",
  min: [-15, 55, -15], max: [15, 85, 15], origin: [0, 64, 0],
  tracks: { blocks: { enabled: true }, entities: { enabled: true } },
  upload_rule: { type: "after_seconds", seconds: 3.0, auto_stop: true },
  report_mode: "summary_diff", detail_limit: 10
})                                                     # → t0 基准 + seq:0
mc_command({ command: "summon minecraft:tnt 0 64 0 {fuse:10}" })   # 1.21.x 的 TNT 引信 NBT 是 fuse
mc_watch_await({ watch_id: "explosion_test_01", after_seq: 0, wait_ms: 15000 })
                                                       # → matched + report（total_changed / affected_bounds / radius / transitions / entity impacts）
mc_watch_stop({ watch_id: "explosion_test_01" })        # 只有 owner 能停；auto_stop 已关时返回 WATCH_NOT_FOUND
```

**规则类型速查**（完整字段见协议 §7.2）：

| upload_rule.type | 何时上传 | 典型场景 |
|---|---|---|
| `after_seconds` / `after_ticks` | 从 start 起固定等待后拍终态 | 爆炸范围、定时药水、红石延时 |
| `settle_quiet` | 区域内连续 N tick 无变化后结算（或 max_wait 兜底） | 未知耗时的连锁反应、水流/岩浆蔓延 |
| `on_condition` | block_match / block_state / entity_removed / entity_hp_below 首次满足 | 核心方块被破坏、机器 lit=true、Boss/测试假人死亡、血量阈值 |
| `interval` | 每 N tick 上报自上次以来的增量 | 机器流水线、持续路径追踪 |
| `event` | 首条匹配日志（`log.line` + 可选正则） | 与命令回执/日志联动的复合测试 |
| `any` / `all` / `sequence` / `repeat` | 组合（嵌套 ≤4、节点 ≤16） | “先炸再等 3s，总超时 15s”；多条件与；周期产出 |

**报告模式**：`summary_diff`（默认，t0 vs t1 汇总）、`timeline_events`（带 tick 的因果顺序）、`snapshot`（触发时刻的过滤后列表）；明细受 `detail_limit` 限制，截断时置 `truncated:true` + `truncated_count`。

**纪律**：

- 一次只等一个结论：`next_seq` 逐份前进；不要用固定 sleep 猜时间。
- `interval` / `repeat` 默认 `auto_stop:false`，不用时 `mc_watch_stop`（owner）或依赖 300s 空闲回收；活跃会话上限 8，区域体积上限 32768 格。
- 报告只给“结论”：明细长度受 `detail_limit`（默认 20，上限 200）限制；需要逐条排查时才把 `report_mode` 换成 `timeline_events` 并调大 `detail_limit`。
- 只信服务端：参考客户端桥接在集成服主线程采样，多人世界回 `WORLD_NOT_LOADED`；需要专用服务端支持时按协议 §7 实现。

### 6.8 常见异常与对策

| 现象 | 原因 | 对策 |
|---|---|---|
| 天眼截图是纯黑/虚空 | 相机处区块未加载 | 看 `chunks.centerLoaded`；`mc_command` 跑 `forceload add <cx> <cz>` 后重拍 |
| 截图带物品栏/血条 | 抓帧时 UI 抑制已撤销（时序问题） | 属桥接端 bug；确认相机覆盖是否活到像素读完 |
| 改了 JAR 但游戏里没生效 | 游戏运行中不能替换 JAR | 退出游戏 → 替换 `mods/*.jar` → 重启 |
| `mc_command` 报权限错 | 单人世界外无 OP | 确认实例是单人/作弊模式；命令需四级权限 |
| 工具报 `BRIDGE_UNREACHABLE` | 游戏没开或端口不对 | `mc_status` 探测；端口默认 25585（`-Dmcb.port=` 改） |
| 截图里是 PauseScreen / 暂停菜单 | 失焦自动暂停，或有人按了 ESC | `mc_screen_current` 看 `screen_class` 与 `pause_on_lost_focus`：失焦导致 → `mc_pause_on_lost_focus({enabled:false})` 防再次弹出；**已弹出的 Screen 没有关屏 op**，需人工关掉或重启（见 §6.6） |
| 截图里是辅助功能引导 / 选项页 | 首次启动 onboarding 挡屏 | 启动前预置 `run/options.txt`（`onboardAccessibility=false` 等，见 `e2e-testing.md`「客户端 run 目录预置」）并重启客户端 |
| 截图默认落在哪 | — | `<gameDir>/screenshots/`（Loom 默认 `run/screenshots/`）；`mc_client_screenshot` 返回**绝对路径**，`mc_screenshot` 只内联不落盘 |
| 启动崩溃 `Mixin transformation of net.minecraft.class_XXX failed` + `ClassCastException: ArrayList cannot be cast to AnnotationNode` | 桥接 jar 用了 `@Redirect`，其 `at()` 注解编码在编译端/运行端 Mixin 版本间不一致（0.2.0 真实事故） | 换 bridge-mod **0.2.1+**（已移除 `@Redirect`）；自己写 Mixin 时见 `pitfalls.md` E5 |
| `mc_client_screenshot` 只回 text + WARNING | MCP 进程读不到截图文件（游戏与 MCP 不同机 / 权限） | 按返回的绝对 `path` 直接取文件；同机时检查文件存在且非空 |
| `mc_reload_resources` 返回 `TIMEOUT` | 资源包大 / 着色器编译慢（30s 看门狗） | 拆小资源包或接受更长耗时；**仍要** `mc_logs` 搜加载错误后再截图 |
| 改了资源包 F3+T 没变化 | 资源没同步进运行 classpath | 先 `./gradlew processResources` 再重载（`fast-iteration.md` 规则 1） |
| `mc_pause_on_lost_focus({enabled:true})` 后画面仍是暂停菜单 | 已打开的 Screen 不会因改选项而关闭 | 该设置只影响**之后**的失焦暂停行为；需要人工关屏后再继续 |
| `[MCB BAD_OP] unknown op: watch.start` | 桥接端 < 0.3.0，不认识 §7 watch op | 升级 bridge-mod 到 0.3.0+；旧桥下降级为 `mc_scan_blocks` / `mc_entities` + 自行对比 |
| `[MCB WATCH_NOT_FOUND]` | watch_id 拼错 / 已 auto_stop 且超过 60s 保留期 / 已被清理 | 用 `mc_watch_list` 看活跃会话；重新 `mc_watch_start` |
| `[MCB RULE_INVALID]` | 规则树深度 >4 / 节点 >16 / 未知 type / 缺必填 / 正则非法 / 非 log.line 事件源 | 按协议 §7.2 修 `upload_rule`；先用最小规则验证 |
| `[MCB TOO_LARGE] ... watch session limit reached` | 活跃 watch 会话已达 8 个 | `mc_watch_list` 挑无用会话 `mc_watch_stop`（owner）后再建 |
| watch 工具回 `[MCB WORLD_NOT_LOADED]` | 多人世界 / 主菜单：参考客户端桥接只在集成服采样 | 单人存档测试，或按协议 §7 为专用服务端实现 watch |

### 6.9 测试世界创建闭环（v0.5.4）

搭场景前先把"世界"这个前提拉干净，再动命令。技能包 v0.5.4 起共 **26 个 `mc_*` 工具**（原 24 个 + `mc_world_create` / `mc_world_delete`，与协议 op 一一对应），闭环：

```text
mc_status()                                            # 1. 基线：worldLoaded=false（停在主菜单）或旧的测试世界
mc_world_create({ name: "mcb_e2e", preset: "flat_clean" })   # 2. 只传 name + preset，其余默认
                                                       #    → world_name / preset / game_mode / difficulty / spawn / seed / gamerules_applied / time_locked
mc_status()                                            # 3. 复查：worldLoaded=true、player.gamemode=creative、timeOfDay=6000、raining=false
mc_command({ command: "gamerule spawn_mobs", side: "server" })   # 4. 需要时核对规则真的落地（回执里读值）
...                                                    # 5. 测试：§6.1–§6.7（命令搭场景 / watch 观察 / 截图验收）
mc_world_delete({ name: "mcb_e2e", force: true })      # 6. 收尾：删存档
```

**要点（v0.5.4）**：

- **参数契约：平时只传 `name` + `preset`**。默认就是创造 + 关结构 + 关自然刷怪 + normal 难度 + 锁正午晴天 + `keep_inventory` + seed 12345（协议 §8.0）；4 套预设 `bedrock_lab` / `flat_clean` / `vanilla_quiet` / `void_platform` 的选择见协议 §8.2 与 `e2e-testing.md`「测试世界基线」。
- **`spawn` 是实际出生点，不是参数复读**：`bedrock_lab` 约 `[0,-61,0]`、`void_platform` 为 `[0,65,0]`。第 3 步确认 `player` 坐标落在附近（没掉虚空）再发后续命令。
- **覆盖默认值只有两个正当理由**：测生存机制 → `game_mode:"survival"`；测结构生成 → `generate_structures:true`。为了"图省事"改成 survival / 开结构，会让观察结果被野怪、村庄、天气污染。
- **fast-iteration**：`clean_if_exists` 默认 true，固定存档名即可每轮秒重建；显式传 `false` 时重复建会得到 `WORLD_ALREADY_EXISTS`。
- **清理纪律**：存档名用 `mcb_` 前缀，测试结束 `mc_world_delete`。安全红线（协议 §8.4）：非 `mcb_` 前缀且无 `.mcb-world.json` 标记的存档 → `WORLD_DELETE_FORBIDDEN`，不要试图绕过。
- **建图 / 删图是单飞的**（协议 §8.7）：并发请求会 `WORLD_IN_USE`，不要并行发两个；`world.create` 默认看门狗 120s、`world.delete` 60s，慢机器显式调 `timeout_ms`（协议 op 字段名 `timeoutMs`，MCP 工具层 `timeout_ms`）。
- **错误码速查**：`BAD_OP`（桥接 Mod < 0.5.0 / 协议 v0.5.4 之前的桥，回退手工进世界）、`PRESET_INVALID`（预设名拼错，见 §8.2 四值）、`CLIENT_ONLY`（专用服桥接无此能力）、`WORLD_NOT_FOUND`（删不存在的存档）、`TIMEOUT`（建图超时）。
- **纪律与 fast-iteration 一致**：建图成功后仍然按 §4 用一次性世界 / 定格截图 / watch 会话观察；`mc_world_delete` 只是把"手动删存档"变成工具调用，不改变"测试状态不留在主世界"的原则（`fast-iteration.md` §7）。
