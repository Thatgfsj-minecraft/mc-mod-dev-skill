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

设计原则：**不针对任何具体模组**。桥接只做四件事——读世界、抓日志、跑命令、截图；一切编解码/游标/重连逻辑都在消费端。

## 2. 什么时候用

| 场景 | 用法 |
|---|---|
| 开发循环里的行为验证（SKILL.md §10 的增强） | `mc_command` 搭场景 → `mc_wait_log` 等结果日志 → `mc_logs` 拉增量 → `mc_map`/`mc_scan` 看世界变化 |
| "编译过了但表现不对"（SKILL.md §11 的第一现场） | `mc_logs(levelMin=ERROR)` 直接看堆栈；`mc_wait_log` 抓异常关键字 |
| 让 AI 建立空间感 | `mc_map` 俯视图 + legend；大区域先 `mc_scan` 过滤关键方块，再 `mc_block_get` 精查 |
| GUI / 视觉类模组 | `mc_screenshot` 给多模态看画面 |
| 模组自检（§10 的启动自检） | 桥接从进程启动就抓日志，游戏启动期的错误也能回看 |

**边界**：MCB 是单人开发工具（客户端桥接 + 集成服务端命令）。E2E 断言基线仍然是专用服务器 + 服务端权威断言（e2e-testing.md）——MCB 补的是"实时看 + 实时操作"，不是替代回归测试。

## 3. 接入步骤（一次性）

1. 构建 `debug-mcp/bridge-mod/`（按版本卡 `bridge.md` 的已验证坐标），jar 放进开发实例 `mods/`。
2. 构建 `debug-mcp/mcp-server/`，在你的 MCP 客户端注册（README 有配置样例）。
3. 启动游戏。桥接监听 `127.0.0.1:25585`（改端口：JVM 参数 `-Dmcb.port=`）。
4. 连通性自检：调 `mc_status`，能返回即链路正常；游戏未启动时工具返回 `BRIDGE_UNREACHABLE`，属预期。

## 4. 使用纪律

1. **先 `mc_status` 再动手**——世界没加载时多数 op 会回 `WORLD_NOT_LOADED`，先建/进世界。
2. **等日志用 `mc_wait_log`，不要盲轮询**——长轮询是协议一等公民，轮询既慢又可能撞游标缺口。
3. **游标纪律**——`mc_logs` 的 `afterId` 自己持有；游标只在同一次游戏会话内有意义：**游戏重启（收到新 hello）后游标归零，从 `afterId=0` 重看**。看到 `overflowed: true` 说明有缺口（协议 §3.7 有精确定义），回 `afterId=0` 重看或直接查崩溃报告。
4. **空间感知三段式**——`mc_map`（看布局）→ `mc_scan`（定位关键方块）→ `mc_block_get`（精查状态/NBT）。直接对大区域硬扫是浪费上下文。
5. **命令失败要读回执**——`mc_command` 返回 `resultText`（命令反馈原文），setblock 打错坐标、give 配错 id 都会在这里显形，不要假设成功。
6. **修改场景用一次性世界**——命令是四级权限，`kill @e` / 大范围 fill 都执行得出去；在专用测试存档里玩（与 e2e-testing.md 的世界纪律一致）。
7. **能"看"就别"动"**——角色不会飞（重力会把他拽下去），移动玩家前先探目标下方有没有可站方块，没有就垫玻璃；`mc_move_player` 已把这条纪律硬编码进桥接端，优先用工具而不是手写命令。只是想看某个位置/方块的外观时，用天眼 `mc_camera_eye`——玩家根本不动，也就没有坠落风险。
8. **天眼的三条工作流**：
   - `mc_camera_look`（"镜头在看哪个方块"）→ 比截图便宜得多，返回命中方块的 id/属性/方块实体 NBT，**优先用它**；
   - `mc_camera_eye`（一次性出图）→ 需要看**外观/渲染效果**时才用；
   - `mc_camera_set` + `mc_screenshot`（持久模式多次取景）→ 需要从多个角度反复观察同一目标时用。
   - ⚠️ mod 信息栏（Jade/WTHIT）**不会跟随虚拟相机**（射线起点固定为玩家眼位），别指望截图里的面板告诉你镜头在看哪。
9. **天眼拍到虚空就是区块没加载**——响应里的 `chunks.centerLoaded` / `loadedNearby` 会如实报；`centerLoaded=false` 时先 `mc_command` 跑 `forceload add <cx> <cz>` 再拍。

## 5. 为新环境写桥接（扩展流程）

1. 读 `debug-mcp/protocol.md`（契约）+ `debug-mcp/bridge-mod/PORTING.md`（移植五步 + 历史易变调用点清单）。
2. 按 SKILL.md §7 对清单逐个核实 API，实现桥接（传输层基本原样搬，世界 API 按目标映射重写）。
3. 验证：编译通过 + 行为测试（至少覆盖 `ping`/`status`/`block.get`/`log.history`/`command.run` 五条主路径）。
4. 按 `references/README.md` 沉淀：目标版本卡目录新建 `bridge.md`（写明验证方式），更新覆盖表，实现放进 `debug-mcp/bridge-mod-<loader>-<版本>/`。

## 6. 实战剧本（怎么把工具串成调试闭环）

以下是真实调试中验证有效的调用序列。工具名以参考实现（15 个 `mc_*` 工具）为准，其他实现按协议 op 对应。

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

### 6.5 常见异常与对策

| 现象 | 原因 | 对策 |
|---|---|---|
| 天眼截图是纯黑/虚空 | 相机处区块未加载 | 看 `chunks.centerLoaded`；`mc_command` 跑 `forceload add <cx> <cz>` 后重拍 |
| 截图带物品栏/血条 | 抓帧时 UI 抑制已撤销（时序问题） | 属桥接端 bug；确认相机覆盖是否活到像素读完 |
| 改了 JAR 但游戏里没生效 | 游戏运行中不能替换 JAR | 退出游戏 → 替换 `mods/*.jar` → 重启 |
| `mc_command` 报权限错 | 单人世界外无 OP | 确认实例是单人/作弊模式；命令需四级权限 |
| 工具报 `BRIDGE_UNREACHABLE` | 游戏没开或端口不对 | `mc_status` 探测；端口默认 25585（`-Dmcb.port=` 改） |
