# MCB v1 — Minecraft 调试桥线协议（Minecraft Console Bridge, version 1）

> 适用环境：环境无关（任何 Loader / 任何版本都可实现本协议）。
> 验证方式：本文是协议规范（契约层）；各环境的实现要点见 `bridge-mod/`（Fabric 1.21.11 参考实现）与对应版本卡的 `bridge.md`。
> 设计目标：**零依赖可实现**——一个只学过 JDK 标准库和目标环境世界 API 的 AI，拿着本文就应该能写出桥接端。

## 0. 设计原则

1. **零依赖传输**：TCP + 每行一个 JSON（NDJSON）。不要求 WebSocket / HTTP 库，JDK `ServerSocket` 即可实现，Node `net.connect` 即可消费。
2. **单向职责**：桥接端（游戏内）只做"读世界 + 抓日志 + 跑命令 + 截图 + 查询/控制客户端状态（屏幕、焦点、资源重载）"；一切协议编解码、游标管理、重连逻辑都在 MCP Server（消费端）。
3. **本地信任**：只监听 `127.0.0.1`。本协议无鉴权——暴露到非回环地址等于把操作员权限交给本机任意进程，这是实现者的责任红线。
4. **消息即行**：一行 = 一个 UTF-8 JSON 对象，以 `\n` 结尾，禁止行内出现裸 `\n`（JSON 字符串里的换行必须转义，标准 JSON 序列化器天然满足）。

## 1. 传输与握手

| 项 | 值 |
|---|---|
| 传输 | TCP，`127.0.0.1`，默认端口 `25585`（可经系统属性 / 环境变量覆盖） |
| 编码 | UTF-8，NDJSON |
| 单行上限 | 1 MiB（含换行符）。桥接端产生的任何单条消息都必须低于此限：日志 `message` 超过 8 KiB 必须截断并追加 `"...(truncated)"`；其余大消息靠 §3.4 体积上限与 NBT 4KB 截断保证。消费端收到超长行应断连并报 `PROTOCOL_MISMATCH` |
| 方向 | 双向：客户端发请求，服务端回响应；服务端可随时推送事件 |
| 并发 | 允许多个客户端同时连接；每个连接独立处理 |

**握手**：TCP 连接建立后，桥接端必须**立即**推送一条 `hello` 事件，消费端据此决定是否继续：

```json
{"event":"hello","data":{"protocol":1,"game":"1.21.11","loader":"fabric","side":"client","worldLoaded":false}}
```

- `protocol` 不等于消费端支持的主版本号时，消费端应断开并报错（主版本号变更 = 不兼容变更）。
- 消费端在收到 `hello` 前发出的请求可能被丢弃，规范要求消费端先等 `hello` 再发请求。

**心跳**：`ping` op（§3），消费端超时未收到 `hello` 或连续两次 `ping` 超时即判定桥接死亡（通常是游戏退出）。

## 2. 消息信封

每一行是且仅是以下三种信封之一。判定规则：有 `id` 且有 `op` → 请求；有 `id` 且有 `ok` → 响应；有 `event` → 事件。

### 2.1 请求（消费端 → 桥接端）

```json
{"id": 42, "op": "block.get", "params": {"x": 0, "y": 64, "z": 0}}
```

- `id`：由消费端生成的正整数，同一连接内单调递增（事件无 id，不会混淆）。
- `op`：op 名，见 §3；`params` 缺省视为 `{}`。

### 2.2 响应（桥接端 → 消费端）

```json
{"id": 42, "ok": true, "data": {"id": "minecraft:stone", "properties": {}}}
{"id": 42, "ok": false, "error": {"code": "WORLD_NOT_LOADED", "message": "no world is loaded"}}
```

- 每个请求必须恰好收到一个响应，失败也必须响应（不许静默吞掉）。
- `error.code` 必须取自 §5 的错误码表，`message` 为人类可读描述（英文，便于日志检索）。

### 2.3 事件（桥接端 → 消费端，单向推送）

```json
{"event":"log","data":{"id":1234,"ts":1731650000123,"level":"ERROR","logger":"net.minecraft.server","thread":"Server thread","message":"..."}}
```

- 事件不携带 `id`，不需要响应。
- 事件可能**在任一响应之前或之后穿插到达**，消费端不得假设顺序。
- **事件推送是尽力而为（best-effort），不是可靠流**：日志的可靠获取渠道是 `log.history` / `log.wait`（§3.7/§3.8，带游标与缺口可见性）。事件流只是便利层，允许在推送缓冲写满时丢弃（丢弃数计入 `status` 的 `droppedEvents`）。
- **背压红线**：桥接端推送事件时**禁止阻塞游戏线程**（游戏线程只做一次非阻塞 `offer` 进有界队列；队列满即丢弃）。这是硬约束——事件推送把游戏卡死是比丢日志严重得多的故障。
- v0.5.2 新增两类事件：`watch.triggered`（规则触发，data 含 watch_id / seq / trigger_reason / report）与 `watch.stopped`（会话结束，data 含 watch_id / reason）。它们同属本节的 best-effort 推送层：可靠获取渠道是 `watch.await`（§7.4）的 seq 游标，事件流只是便利层（详见 §7.8）。

## 3. Op 全集

命名约定：`域.动作`，全小写。工具实现（MCP Server）应与 op 一一对应，不引入 op 之外的第二套抽象。

### op 总览

| op | 章节 | 一句话 |
|---|---|---|
| `ping` | §3.1 | 连通性探测 |
| `status` | §3.2 | 世界 / 玩家 / 桥接状态快照（永远可查） |
| `block.get` | §3.3 | 单方块状态 + 方块实体 |
| `block.scan` | §3.4 | 区域方块扫描 |
| `map.ascii` | §3.5 | 俯视图 / 切片字符画 |
| `entity.list` | §3.6 | 区域实体列表 |
| `log.history` | §3.7 | 日志增量拉取（缺口可见） |
| `log.wait` | §3.8 | 日志长轮询（"跑命令 → 等那条日志"） |
| `command.run` | §3.9 | 命令执行；`side` 选择服务端或客户端通道（v0.5.1 修订） |
| `player.info` | §3.10 | 玩家详情 |
| `screenshot.take` | §3.11 | 截图，PNG base64 内联 |
| `camera.eye` / `camera.set` / `camera.clear` | §3.12 | 天眼自由相机 |
| `player.move` | §3.13 | 带地面安全的传送 |
| `camera.look` | §3.14 | 天眼射线查询 |
| `screen.current` | §3.15 | 当前屏幕 / 窗口焦点 / 失焦暂停开关（v0.5.1 新增） |
| `client.screenshot` | §3.16 | 截图落盘到 `screenshots/`（v0.5.1 新增） |
| `client.pause_on_lost_focus` | §3.17 | 失焦暂停开关的读 / 写（v0.5.1 新增） |
| `client.reload_resources` | §3.18 | 资源包热重载（v0.5.1 新增，L2 闭环配套增量） |
| `watch.start` | §7.1 | 建立观察会话 + t0 基准快照（v0.5.2 新增） |
| `watch.await` | §7.4 | 长轮询 / 游标拉取规则触发报告（v0.5.2 新增） |
| `watch.snapshot` | §7.5 | 立即强制出一次 t1 vs t0 对比报告（v0.5.2 新增） |
| `watch.list` | §7.6 | 查看活跃 watch 会话元数据（v0.5.2 新增） |
| `watch.stop` | §7.7 | 手动终止会话并释放快照（v0.5.2 新增） |

### 3.1 `ping`

请求参数：无。响应 data：`{"pong": true, "uptimeMs": 123456}`。
用途：连通性探测。桥接端必须响应 `ping`，即使世界未加载。

### 3.2 `status`

请求参数：无。响应 data：

```json
{
  "game": "1.21.11", "loader": "fabric", "side": "client",
  "worldLoaded": true, "dimension": "minecraft:overworld",
  "timeOfDay": 12345, "raining": false, "thundering": false,
  "droppedEvents": 0,
  "player": {"x": 0.5, "y": 64.0, "z": 0.5, "yaw": 0.0, "pitch": 0.0,
             "gamemode": "creative", "health": 20.0, "present": true}
}
```

- 世界未加载时 `worldLoaded` 为 `false`，其余世界相关字段为 `null`，**不得报错**（`status` 永远可查）。
- `droppedEvents`：自进程启动以来因推送队列满而被丢弃的 log 事件数（§2.3 背压策略的可见性指标）。

### 3.3 `block.get`

参数：`x`,`y`,`z`（整数）；可选 `dimension`（字符串，缺省为玩家所在/主世界）。
响应 data：

```json
{
  "x": 0, "y": 64, "z": 0,
  "id": "minecraft:oak_log",
  "properties": {"axis": "y"},
  "blockEntity": {"type": "minecraft:chest", "nbt": "{...}"},
  "hasBlockEntity": true
}
```

- `properties` 只含非默认值可省略，含全部属性亦可；桥接端自行选择并在 hello 后的 `status`/文档中保持一致。
- `blockEntity` 仅在方块确实有方块实体时出现；`nbt` 为字符串化的 SNBT（体积由桥接端自控，建议截断到 4KB 并追加 `"...(truncated)"`）。

### 3.4 `block.scan`

参数：`min` 与 `max`（各为 `[x,y,z]` 整数数组，**两个端点按分量任意先后均可——桥接端必须按轴归一化（每轴取较小者为下界），消费端无需预处理**）；可选 `filter`（方块 id 或 `#tag` 数组）；可选 `includeProperties`（布尔，默认 false）。

响应 data：`{"count": 12, "truncated": false, "blocks": [{"x":..,"y":..,"z":..,"id":"..","properties":{...}}]}`

约束：

- 体积上限 `4096` 个方块（三个轴跨度乘积超过上限必须报 `TOO_LARGE`，而不是悄悄截断坐标范围）。本约束同样适用于 §3.5 `map.ascii` 与 §3.6 `entity.list` 的区域参数。
- `limit` 过滤：`filter` 存在时，`limit` 与 `truncated` 作用于**过滤后**的结果集。
- 结果数超过 4096 时 `truncated: true`，只返回前 4096 个（按 x→y→z 字典序），保证两次相同扫描结果一致。
- `filter` 中 `#tag` 表示标签匹配（如 `"#minecraft:logs"`）。**tag 支持是可选能力**：桥接端不支持标签时，对含 `#tag` 的 filter 应返回 `BAD_PARAMS` 并在 message 说明，禁止静默把 tag 当字面 id 或匹配空集。

### 3.5 `map.ascii`

参数：`min` 与 `max`（各 `[x,y,z]`，同 §3.4 体积约束）；可选 `mode`（`"top"` 默认 / `"slice"`）；`mode:"slice"` 时必须给 `y`。

语义：

- `top`：俯视图。对每个 `(x,z)` 列，在 `[min.y, max.y]` 内**自上而下**找第一个非空气方块，以其为该格内容。这是"这个区域长什么样"的默认问法。
- `slice`：剖面/切片。固定 `y` 层，显示该层每个 `(x,z)` 的方块（含空气）。

响应 data：

```json
{
  "mode": "top", "min": [0, 60, 0], "max": [15, 70, 15],
  "viewX": "west→east", "viewZ": "north→south",
  "legend": {"S": "minecraft:stone", "W": "minecraft:oak_log", ".": "minecraft:air"},
  "rows": ["SWWW....", "SWWW....", "..."]
}
```

- 字符分配规则（桥接端必须遵守，消费端不必解析 legend 之外的语义）：`minecraft:air` 固定 `.`；其余按方块 id path 首个字母小写分配，冲突时依次尝试 path 的后续字母，再冲突时用备用字符集 `#@%*+=~^!?:;` 顺序取用。
- `rows[i]` 对应 `min.z + i` 行，每行自 `min.x` 到 `max.x`。行列上限各 256，超出报 `TOO_LARGE`。
- `viewX` / `viewZ` 告诉 AI 坐标轴方向，防止镜像混淆——这是给 AI 空间感的关键字段，不许省略。

### 3.6 `entity.list`

参数：`min` 与 `max`（同 §3.4 体积约束）；可选 `type`（实体类型 id 或 `#tag` 数组）。

响应 data：`{"count": 2, "truncated": false, "entities": [{"type":"minecraft:item","uuid":"...","name":"Iron Ingot","x":..,"y":..,"z":..,"health":null}]}`

- `health` 仅对 `LivingEntity` 类实体有效，其余为 `null`。
- 结果数上限 512，超出 `truncated: true`。

### 3.7 `log.history`

参数：可选 `afterId`（长整数，默认 0）、`limit`（默认 500，上限 1000）、`levelMin`（`DEBUG`/`INFO`/`WARN`/`ERROR`，默认 `INFO`）。

响应 data：`{"entries": [{"id":..,"ts":..,"level":..,"logger":..,"thread":..,"message":..}], "nextId": 1235, "overflowed": false}`

**游标语义（规范定义，两种实现都必须一致，否则消费端增量拉取必然漏读或重读）：**

- 日志条目 `id` 在桥接端**进程生命周期内**从 1 开始单调递增；缓冲区持有的是后缀（环形淘汰只丢最老的）。
- `afterId` 增量拉取只返回 `id > afterId` 且通过 `levelMin` 过滤的条目；`limit`（默认 500，上限 1000）作用于**过滤后**的条目数。
- **`nextId` = 本页扫描时缓冲区中最新的原始条目 id**（不是"最后一条匹配项的 id+1"）。这样即使某页过滤后为空，游标也能推进到当前最新位置，不会在无匹配日志时空转。
- **`overflowed` = 缓冲区最老条目 id > afterId + 1**（即 (afterId, 最老条目) 区间内的原始条目已被淘汰）。`overflowed: true` 表示存在**无法恢复的缺口**（**缺口必须可见，不许静默**——这是本协议从同类工具学来的最重要的一课）。afterId=0 的首页同理：条目从 1 开始编号，最老条目 id > 1 即溢出。
- `levelMin` 是过滤条件（读时过滤，不是缓冲写入过滤）——缓冲区必须始终包含全部级别，否则 levelMin 切换会看到不同的"历史"。
- 游标跨游戏重启无意义：游戏重启后 id 可能重置。**消费端必须在每次收到 `hello` 时把游标归零**（§1），从 afterId=0 重看。
- 桥接端必须从进程启动就开始捕获日志（不是连接建立后才开始），缓冲区建议 10000 条。

### 3.8 `log.wait`

参数：`pattern`（正则，按整条 message 匹配）；可选 `levelMin`（默认 `INFO`）、`timeoutMs`（默认 10000，上限 60000）、`afterId`（默认 0）。

响应 data：`{"matched": true, "entry": {...}}` 或 `{"matched": false, "nextId": 1235}`。

- **长轮询**：桥接端阻塞等待新日志匹配为止（不是扫历史）。消费端用它实现"执行命令 → 等那条日志"，替代盲轮询。
- `afterId` 同时决定了历史回看起点：先检查历史缓冲中 `id > afterId` 的条目，无匹配再进入等待。

### 3.9 `command.run`

参数：`command`（字符串，**必填**；容忍前导 `/`，桥接端自行剥除）；可选 `side`（`"auto"` / `"server"` / `"client"`，默认 `"auto"`）；可选 `timeoutMs`（默认 10000，上限 60000）。

响应 data（v0.5.1 起；`returnValue` / `messages` / `side` 为**新增字段**，旧消费端可忽略）：

```json
{"success": true, "returnValue": 1, "resultText": "Set the time to noon", "feedback": ["Set the time to noon"], "messages": ["Set the time to noon"], "side": "server"}
```

执行语义（按 `side` 路由）：

- `side="server"`：存在集成服务端（单人存档）→ 在**服务端线程**以四级权限（`ALL_PERMISSIONS`）执行，收集命令反馈。无集成服务端 → `WORLD_NOT_LOADED`。
- `side="client"`：走客户端命令通道 `ClientPacketListener.sendCommand(String)`（参数**不带**前导 `/`），适用于多人世界 / 无集成服务端的场景；客户端侧注册的命令经 Loader API 时由该通道分发。无连接 → `WORLD_NOT_LOADED`。该通道**无法捕获回显**：`resultText` 为 `""`、`messages` 为 `[]`、`returnValue` 为 `null`。
- `side="auto"`：有集成服务端走 `server`，否则有连接走 `client`，否则 `WORLD_NOT_LOADED`（= 旧版行为，只多返回 `side` 字段）。

字段语义：

- `success`：命令是否成功执行（解析失败 / 执行异常为 `false`）。
- `returnValue`：Brigadier `dispatcher.execute(ParseResults)` 的 int 返回值；`client` 路径或失败为 `null`。
- `resultText`：人类可读的汇总文本（反馈 + 错误信息；超长截断见 §1）。
- `feedback`：命令逐条反馈的字符串数组（**旧字段**，保留兼容）。
- `messages`：与 `feedback` **同内容**的数组（设计文档使用的字段名）；两者必须一致，消费端任取其一。
- `side`：本次实际执行的路径（`"server"` / `"client"`）。

失败（解析 / 执行异常）：`success:false`、`returnValue:null`、`resultText` 为错误文本、`messages:[]`（因两者必须一致，`feedback` 同样为空）。

- 命令执行必须有超时保护，防止 `kill @e` 之类命令挂死桥接；`timeoutMs` 到期仍未完成响应 `TIMEOUT`。

### 3.10 `player.info`

参数：无。响应 data：

```json
{
  "present": true, "name": "Player", "uuid": "...",
  "x": 0.5, "y": 64.0, "z": 0.5, "yaw": 0.0, "pitch": 0.0,
  "gamemode": "creative", "health": 20.0, "food": 20.0,
  "dimension": "minecraft:overworld",
  "hotbar": [{"slot": 0, "id": "minecraft:diamond_pickaxe", "count": 1}]
}
```

- 玩家不存在时 `present: false`，其余字段 `null`，**不得报错**。

### 3.11 `screenshot.take`

参数：无（v1 不带裁剪）。响应 data：`{"format":"png","width":..,"height":..,"dataBase64":"..."}`

- **PNG 格式**（原版截图编码器的原生输出）。消费端按响应的 `format` 字段动态选择 mimeType，**禁止写死**。
- **仅客户端可实现**。专用服务端实现应返回 `CLIENT_ONLY`。
- 尺寸超过 1280 宽时建议等比缩小（消费端模型上下文有限）——推荐实现，非硬约束。
- 实现必须把像素读取投递到渲染线程执行，禁止在 TCP 线程碰 GL 上下文（见 bridge-mod 实现要点）。

### 3.12 `camera.eye` / `camera.set` / `camera.clear`（天眼自由相机，v1 后向兼容扩展）

动机：AI 想看某个位置时不必 `tp` 玩家过去（玩家会下落、状态会被污染）。桥接端在**渲染线程的相机装配点**临时覆盖相机姿态，渲染一帧后抓帧恢复——玩家不动，一帧延迟（~16ms）。

#### `camera.eye`（一次性）

参数：`x`,`y`,`z`（**双精度浮点**，相机眼位，不是方块坐标）、`yaw`（浮点，0=南/+Z，90=西/-X，180=北/-Z，270=东/+X）、`pitch`（浮点，-90=正上，0=水平，90=正下）；可选 `fov`（浮点，度，覆盖投影 FOV，缺省沿用玩家 FOV）。

响应 data：

```json
{
  "format": "png", "width": 1280, "height": 720, "dataBase64": "...",
  "pose": {"x": 24.5, "y": -61.0, "z": 8.2, "yaw": 90.0, "pitch": 10.0, "fov": 60.0},
  "chunks": {"centerLoaded": true, "loadedNearby": 25, "nearbyWindow": 25}
}
```

- `pose`：本次实际使用的相机姿态（`fov` 为 `null` 表示未覆盖）。
- `chunks`：**区块加载可见性**（天眼唯一的坑）。`centerLoaded=false` 或 `loadedNearby` 明显小于 `nearbyWindow` 说明相机处客户端区块未加载，拍到的是虚空；消费端应先 `command.run` 执行 `forceload add <chunkX> <chunkZ>`（或把玩家 TP 过去加载）再拍。
- 姿态覆盖发生在原版 `Camera.setup` 之后、准星射线检测之前，因此 **Jade/WTHIT 信息栏跟随虚拟相机**——这是本 op 的免费赠品。
- 世界未加载 → `WORLD_NOT_LOADED`；渲染管线长时间未产帧（如最小化且暂停渲染）→ `TIMEOUT`（10s，同 §3.11 看门狗）。
- 等待期间被 `camera.clear` 打断 → 以 `INTERNAL` 报 `camera request cancelled by camera.clear`（保证 §2.2 恰好一个响应）。

#### `camera.set`（持久模式）

参数同 `camera.eye`。响应 data：`{"active": true, "mode": "persistent", "pose": {...}}`

进入自由相机：每帧都覆盖姿态，直到 `camera.clear`。配合 `screenshot.take` 可多次取景；准星射线（Jade）同样跟随。玩家本体不动、不受影响。

#### `camera.clear`

参数：无。响应 data：`{"active": false, "mode": "none"}`

退出自由相机，恢复角色视角；同时取消未完成的 `camera.eye`（见上）。

- `status`（§3.2）新增可选字段 `cameraMode`：`"none" | "one-shot" | "persistent"`。
- **UI 抑制（硬编码行为）**：相机 op 生效期间，桥接端屏蔽**原版 HUD 杂项**（物品栏/血量饥饿/经验、状态效果、聊天+动作栏、Tab 列表、计分板侧栏、Boss 条、字幕、准星、demo/sleep 覆盖层、**F3 调试文字**、**物品名弹窗**、**保存指示器**），截图干净；**mod 信息覆盖层（minihud、Xaero 路经点、Jade 等）不受影响、照常渲染**。屏幕效果（vignette/传送门等）保留，属于世界观感。相机 op 结束（抓帧完成 / `camera.clear`）自动恢复。
  - 时序要求：相机覆盖必须在**像素读取完成之后**才撤销，否则抓到的是 HUD 已恢复的帧（一次性 `camera.eye` 曾因此带上物品栏）。
  - **第一人称手臂不在 `Gui` 里**：它由 `GameRenderer.renderItemInHand` 在世界渲染阶段画，屏蔽它必须去 `GameRenderer`，在 `Gui` 里怎么改都清不掉。`[实测]`
- **Jade/WTHIT 信息栏会跟随虚拟相机**（实测 + Jade 21.1.1 字节码验证）：`perspectiveMode: CAMERA` 下 Jade 用 `camera.position()` 当射线起点、`camera.forwardVector()` 当方向，方块高亮框会精确套在虚拟相机准星指着的方块上。
  - ⚠️ 前置条件：Jade 的 `displayMode` 必须是 `LITE`；`TOGGLE`/`HOLD_KEY` 需要按住 HUD 键，AI 按不了。
  - ⚠️ Jade 的射线在 tick 阶段算，比渲染滞后一帧；持续模式下无感知。
  - 即便 Jade 不可用，§3.14 `camera.look` 仍可用来便宜地问"镜头正对着哪个方块"。
- 实现要点：姿态覆盖与 FOV 覆盖**必须在渲染线程**完成（Fabric 参考实现走 Mixin：`Camera.setup` TAIL + `GameRenderer.getFov` TAIL）；抓帧复用 §3.11 的像素读取路径，在两帧之间读取仍持有虚拟相机画面的帧缓冲。
  - Mixin 注入若要改返回值，**必须写 `cancellable = true`**，否则 `cir.setReturnValue` 会抛 `CancellationException` 炸掉渲染线程（`GameRenderer.getFov` 曾因此崩溃）。

### 3.13 `player.move`（带地面安全的传送，v1 后向兼容扩展）

动机（用户硬性规则）：**角色不会飞**。任何传送前必须探测目标下方有没有可站立方块，虚空就先垫玻璃再传——把这条纪律直接做进桥接端，消费端（AI）不可能忘记。

参数：`x`,`y`,`z`（**双精度**，目标脚部位置）；可选 `yaw` / `pitch`（浮点，缺省保持玩家当前视角）。

桥接端硬编码流程：

1. 探测 `(x, y-1, z)`，无则探测 `(x, y-2, z)`（可站立 = 非空气且非流体）。
2. `(x, y-1, z)` 可站 → 脚部 `y`；仅 `(x, y-2, z)` 可站 → 脚部 `y-1`；都没有 → 先放置 `minecraft:glass` 于 `(x, y-1, z)`（**服务端权威**：走集成服务端 setBlock，客户端 setBlock 会产生幽灵方块），再传到 `y`。
3. 传送后速度归零、摔落距离清零（`absSnapTo` + `setDeltaMovement(0)` + `resetFallDistance`）。
4. **垫玻璃失败时不移动玩家**（宁可不动，不许掉虚空），报 `INTERNAL` 并说明。

响应 data：

```json
{
  "success": true, "moved": true,
  "x": 24.0, "y": -63.0, "z": 8.0, "yaw": 0.0, "pitch": 0.0,
  "dimension": "minecraft:overworld",
  "groundWasMissing": true, "placedGlass": true, "placedGlassVerified": true
}
```

- `y` 是**实际脚部高度**，可能比请求低一格（地面在下方时）。
- `groundWasMissing`：探测时目标下方无直接可站方块（含"落到低一格"情形）。
- `placedGlassVerified`：玻璃已确认放置（集成服务端路径 true）。多人世界无集成服务端时降级为发送 `setblock` 命令（需要 OP，无法确认结果，`placedGlassVerified: false`）。
- 无集成服务端也无连接 → `INTERNAL`；无世界/玩家 → `WORLD_NOT_LOADED`。
- 世界未加载时玻璃探测读到的都是空气，会按"虚空"处理——集成服务端 setBlock 会强制加载区块，行为安全。

### 3.14 `camera.look`（天眼看向哪个方块，v1 后向兼容扩展）

动机：AI 需要"镜头正对着哪个方块、它是什么状态"——比截图便宜得多，也不受区块外的渲染限制。虽然 Jade 在 `displayMode: LITE` + `perspectiveMode: CAMERA` 下已经会跟随虚拟相机（见 §3.12），但 `camera.look` 给的是**结构化数据**（方块 id、属性、方块实体 NBT、精确坐标），不用去读图，也能在 Jade 不可用时兜底。

参数：`x`,`y`,`z`（**双精度**，相机眼位）；二选一：

- `yaw` + `pitch`（浮点，同 §3.12 角度约定），或
- `lookAt`：`[x,y,z]` 目标点数组，桥接端自行换算 yaw/pitch。

可选 `reach`（双精度，最大射线长度，默认 64）。

响应 data：

```json
{
  "originX": 13.5, "originY": 62.5, "originZ": 9.5,
  "yaw": 135.0, "pitch": 35.0, "reach": 64.0,
  "directionX": -0.62, "directionY": -0.57, "directionZ": 0.54,
  "hit": true, "hitType": "BLOCK", "distance": 2.83,
  "x": 12, "y": 61, "z": 8,
  "id": "fabricaeexnihilo:porcelain_crucible",
  "properties": {}, "hasBlockEntity": true,
  "blockEntity": {"type": "...", "nbt": "{...}"}
}
```

- 命中方块的字段（`x`/`y`/`z`/`id`/`properties`/`blockEntity`）与 §3.3 `block.get` **完全一致**，可直接复用解析逻辑。
- `hit: false` 表示射线上没有任何方块（`hitType: "MISS"`）。
- 射线在**客户端世界**上计算，与渲染帧用的方块数据同源，因此结论与画面一致。
- 与 `camera.eye`/`camera.set` 配合使用：先用 `camera.look` 确认目标，再用 `camera.eye` 拍图。

### 3.15 `screen.current`（v0.5.1 新增）

参数：无。响应 data：

```json
{"in_world": true, "screen_class": null, "screen_title": null,
 "window_focused": false, "pause_on_lost_focus": false,
 "player": {"dimension": "minecraft:overworld", "pos": [12.5, 64.0, -8.2], "game_mode": "creative"}}
```

- `in_world`：世界是否已加载。
- `screen_class`：`Minecraft.screen` 的 FQCN（如 `net.minecraft.client.gui.screens.PauseScreen`），无屏幕为 `null`。
- `screen_title`：`Screen.getTitle().getString()`，无屏幕为 `null`。
- `window_focused`：`Minecraft.isWindowActive()`——**与失焦暂停判定用的是同一个原版谓词**（§3.17 / bridge-mod 的守卫都依赖它）。
- `pause_on_lost_focus`：`Options.pauseOnLostFocus` 的当前值（由 `options.save()` 持久化到 `options.txt`）。
- `player`：世界未加载时为 `null`；`pos` 为玩家**脚部**坐标（double 三元组），`game_mode` 取 `mc.gameMode.getPlayerMode().getName()`（如 `creative`）。
- 用途：诊断"画面是不是被 PauseScreen 挡住 / 窗口失焦导致暂停 / 当前在哪个 GUI"——不读一个像素就能回答，比截图便宜得多。
- **永远成功**（除 `INTERNAL`），不因世界未加载报错。

### 3.16 `client.screenshot`（v0.5.1 新增，文件落盘版）

参数：可选 `filename`（默认 `mcb-<epochMillis>.png`；**只取 basename**，过滤 `[^A-Za-z0-9._-]`，无 `.png` 后缀时补上）；可选 `hide_hud`（bool，默认 `false`）。

响应 data：

```json
{"path": "/abs/run/screenshots/mcb-1712345678901.png", "width": 1280, "height": 720,
 "screen_class": null, "hide_hud": false, "bytes": 123456}
```

- 与 §3.11 `screenshot.take` **并存**：`screenshot.take` 内联 base64（适合快速看图），本 op 落盘并返回绝对路径（适合"验收截图"留档、后续反复比对）。
- 写 `Minecraft.gameDirectory/screenshots/`（原版截图目录）；**文件写完才响应**，`path` 为绝对路径，`bytes` 为文件字节数。
- 在**帧末渲染线程**抓帧（读取渲染帧缓冲）——像素只能在渲染线程读，见 §6。
- `hide_hud=true`：临时置 `options.hideGui=true`，**必须在被捕获的那一帧渲染之前生效**，并在**像素读取完成后立即恢复原值**（只影响该帧）；它不隐藏已经打开的 `Screen`（GUI 界面照常出现在截图里）。
- `screen_class`：抓帧时 `Minecraft.screen` 的 FQCN，用来判断"截图里是不是 PauseScreen 挡着画面"。
- 仅客户端可实现；专用服务端返回 `CLIENT_ONLY`。
- 看门狗 10s → `TIMEOUT`。
- 线程纪律：像素读取必须在渲染线程帧末；PNG 编码与写盘在 IO 线程（`Util.ioPool()`），写盘完成后才发响应（§6）。

### 3.17 `client.pause_on_lost_focus`（v0.5.1 新增）

参数：可选 `enabled`（bool）；**缺省 = 只读查询**。

响应 data：`{"enabled": false, "auto_pause_guard": true}`

- 无 `enabled`：只返回当前状态，不修改任何东西。
- 传 `enabled`：设置 `options.pauseOnLostFocus = enabled` 并 `options.save()` 持久化；同时联动桥接端守卫：`enabled=true` → `auto_pause_guard=false`（**恢复原版失焦自动暂停**，供兼容性测试）；`enabled=false` → `auto_pause_guard=true`。
- `auto_pause_guard`：桥接端是否屏蔽"原版失焦自动暂停"（默认 `true`）。实现方式 = 启动时强制关掉 `pauseOnLostFocus`，并在`auto_pause_guard` 为真期间**每渲染帧校正一次该上游开关**（F3+P / 选项重载 / 其他 Mod 把它改回 `true` 时会被掰回 `false`）。守卫**不注入 `pauseGame` 调用点**——见 §6 第 6 条。
- ⚠️ 该守卫**只针对失焦自动暂停**，**不影响 ESC 手动暂停**：ESC 走 `KeyboardHandler` → `Minecraft.pauseGame(boolean)`，不读这个开关（详见 §6 第 6 条与 bridge-mod README 红线）。
- **永远成功**（除 `INTERNAL`）。

### 3.18 `client.reload_resources`（v0.5.1 新增，L2 闭环配套）

参数：无。响应 data：`{"reloaded": true, "duration_ms": 1234}`

- 客户端主线程调用 `Minecraft.reloadResourcePacks()` → `CompletableFuture<Void>`；future 完成（成功**或异常**）后响应；重载抛异常 → `INTERNAL`。
- 看门狗 30s → `TIMEOUT`（资源包大 / 着色器编译慢时超时是真实可能）。
- **增量扩展说明**：设计文档并未列出此 op，它属于"**L2 资源热重载闭环**"的配套增量（改完资源包不重启游戏即可验收：热重载 → 查日志错误 → 截图）。它**不改变**协议主版本（§4）。
- 仅客户端；专用服务端返回 `CLIENT_ONLY`。
- 本 op 只保证"重载流程已结束"，不保证"资源都加载成功"——加载错误必须配合 `log.history` / `log.wait` 检索（`Missing` / `Unable to load model` / `Unknown item` 等）。

## 4. 版本化

- `protocol` 主版本号出现在 `hello` 中。**新增 op、新增可选参数、新增响应字段 = 向后兼容，主版本号不变**。v0.5.1 新增的 §3.15–§3.18 四个 op 与 §3.9 的新增响应字段（`returnValue` / `messages` / `side`）都属这一类：`hello.protocol` 仍为 `1`，消费端必须忽略不认识的响应字段而不是报错。
- v0.5.2 新增 §7 的五个 `watch.*` op、两种事件与相关可选字段，同样属于**纯新增**：`hello.protocol` 仍为 `1`。旧消费端对 `watch.*` 收到 `BAD_OP` 时应降级为轮询（`block.scan` / `entity.list` + 自行对比），不得判定协议不兼容。
- 删除/改名 op、改变字段语义、改变信封结构 = 主版本号 +1。
- 消费端应对未知 op 回 `BAD_OP` 而不是断连——向前兼容由消费端降级，桥接端保持简单。

## 5. 错误码表

| code | 语义 |
|---|---|
| `BAD_OP` | 未知 op |
| `BAD_PARAMS` | 参数缺失 / 类型错误 / 越界 |
| `TOO_LARGE` | 扫描体积或结果数超限 |
| `WORLD_NOT_LOADED` | 需要世界但世界未加载 |
| `CLIENT_ONLY` | 该能力仅客户端桥接可实现（专用服务端返回） |
| `TIMEOUT` | op 内部等待超时（log.wait / command.run） |
| `INTERNAL` | 桥接端内部异常（message 带 Java 类名便于排障） |
| `WATCH_NOT_FOUND` | watch_id 不存在 / 已停止且超过 60s 报告保留期（v0.5.2，§7.11） |
| `RULE_INVALID` | watch 规则树结构错误（嵌套深度 / 节点数 / 未知 type / 缺参 / 参数越界 / 正则非法，v0.5.2，§7.11） |

## 6. 实现者的线程纪律（规范要求，两种实现都适用）

1. TCP 接收/发送在独立 IO 线程；**任何世界/实体/方块读取必须投递到游戏主线程**执行后回填响应（游戏 API 不是线程安全的，这条纪律违反了会产生随机崩溃，而且是本协议所有实现里最难查的坑）。
2. 响应顺序不作保证——桥接端允许并发处理长轮询；消费端按 `id` 匹配。
3. 桥接端崩溃（`INTERNAL`）不得关闭 TCP 连接；只有游戏退出才断连。
4. **截图**（§3.11 / §3.16）：帧缓冲 / GL 像素访问必须在**渲染线程的帧末**执行。§3.16 的 PNG 编码与写盘在 IO 线程（`Util.ioPool()`），**写盘完成后**才发响应；`hide_hud` 的临时值必须在**被捕获帧渲染前生效**、在**同一次像素读取回调内**恢复，否则会漏改全局状态或抓到 HUD 已恢复的帧。
5. **屏幕 / 暂停 / 资源重载**（§3.15 / §3.17 / §3.18）：读 `Minecraft.screen` / 读写 `Options` 必须在**客户端主线程**；`reloadResourcePacks()` 也在客户端主线程发起，future 完成后可从任意线程发响应（响应唯一性仍按 §2.2）。
6. **失焦暂停守卫优先维护上游开关**（`Options.pauseOnLostFocus`，启动关一次 + 运行期每帧校正），**不要拦 `pauseGame(boolean)` 全局入口**——ESC 也走同一个入口（见 §3.17 与 bridge-mod README 红线）。如果确实要拦渲染循环里的失焦调用点，**不要用 `@Redirect`**：`@Redirect.at()` 在 sponge-mixin 0.17.0（单值 `At`）与 0.17.4（`At[]`）之间注解编码不同，用新版编译、旧 Loader 运行的 jar 会在 Mixin 变换阶段抛 `ClassCastException: ArrayList cannot be cast to AnnotationNode` 直接崩启动（真实事故，详见 `pitfalls.md` E5）；`@Inject.at()` 在两个版本都是 `At[]`，是安全的注入方式。

## 7. Watch 会话引擎（v0.5.2 新增，MCB v1 后向兼容扩展）

> 动机：做爆炸范围、连锁破坏、实体 AI / 伤害这类**有观察窗口**的实验时，逐次轮询 §3.4 / §3.6 再自己算 diff 既慢又容易错过触发时刻。本节的思路是把“什么时候发、发什么粒度”从消费端搬到桥接端：AI 先立一个基准快照（t0），声明规则，桥接端在服务端主线程持续比较，命中后一次性回传高价值结论（t1）。
>
> 一次会话由三个可组合的积木构成：**观测范围**（包围盒 + 方块/实体过滤）、**上传规则**（§7.2）、**报告模式**（§7.3）。
>
> 版本：本节的 5 个 op 与 2 个事件均为新增（§4）。`hello.protocol` 仍为 `1`；旧消费端遇到未知 op 会得到 `BAD_OP`，应降级为轮询，不视为协议不兼容。
>
> 参考实现限制：本仓库的 Fabric 1.21.11 客户端桥接只在**集成服务端**可用（服务端权威采样），多人世界 / 专用服务端需由对应实现按本节落地；这不是协议限制。

### 7.1 `watch.start` — 建立观察会话与基准快照

请求：

```json
{
  "id": 101,
  "op": "watch.start",
  "params": {
    "watch_id": "explosion_test_01",
    "min": [-15, 55, -15], "max": [15, 85, 15],
    "origin": [0, 64, 0],
    "dimension": "minecraft:overworld",
    "tracks": {
      "blocks": {"enabled": true, "ignore_air_to_air": true, "include_nbt": false,
                 "filter": ["minecraft:tnt", "mymod:*"]},
      "entities": {"enabled": true, "exclude_players": false,
                   "type_filter": ["minecraft:*"], "fields": ["pos", "hp", "alive"]}
    },
    "upload_rule": {"type": "after_seconds", "seconds": 3.0, "auto_stop": true},
    "report_mode": "summary_diff",
    "detail_limit": 20
  }
}
```

字段：

| 字段 | 类型 | 约束 / 默认 |
|---|---|---|
| `watch_id` | string | 必填；全局唯一；正则 `^[A-Za-z0-9_-]{1,64}$`；**不允许含 `.`**（点号保留给协议命名空间）；重复（含 60s 报告保留期内的已关闭会话）→ `BAD_PARAMS` |
| `min` / `max` | int[3] | 必填；两个端点按分量任意先后均可，桥接端**必须按轴归一化**（每轴取较小者为下界）；三轴跨度乘积 > `32768` → `TOO_LARGE` |
| `origin` | int[3] | 可选；报告里 `max_radius_from_origin` 的参考中心；缺省则该项为 `null` |
| `dimension` | string | 可选；缺省为玩家所在维度（集成服）或主世界；维度不存在 / 世界未加载 → `WORLD_NOT_LOADED` |
| `tracks.blocks.enabled` | bool | 默认 `true`；`false` 表示不采样/不比对方块 |
| `tracks.blocks.ignore_air_to_air` | bool | 默认 `true`；`true` 时空气→空气不计变化（也就不会触发静默结算） |
| `tracks.blocks.include_nbt` | bool | 默认 `false`；`true` 时快照额外记录方块实体 SNBT（报告里按 `detail_limit` 截断） |
| `tracks.blocks.filter` | string[] | 默认空 = 全部；元素为方块 id、`*` 通配（如 `mymod:*`）或 `#tag`（tag 不支持时回 `BAD_PARAMS`，禁止静默当字面 id） |
| `tracks.entities.enabled` | bool | 默认 `true` |
| `tracks.entities.exclude_players` | bool | 默认 `false`；`true` 时玩家不计入实体轨道 |
| `tracks.entities.type_filter` | string[] | 默认空 = 全部；实体类型 id / `*` 通配 / `#tag` |
| `tracks.entities.fields` | string[] | 默认 `["pos","hp","alive"]`；可选值 `pos` / `hp` / `alive` / `name`；`type` 与 `uuid` 恒在 |
| `tracks.entities.pos_epsilon` | number | 默认 `0.25`；`timeline_events` 下小于该距离的位移不计（`0 ~ 16`） |
| `tracks.entities.hp_epsilon` | number | 默认 `0.5`；`timeline_events` 下小于该幅度的血量变化不计（`0 ~ 1000`） |
| `upload_rule` | object | 必填；见 §7.2；深度 ≤4、节点数 ≤16、序列化后 ≤16 KiB，否则 `RULE_INVALID` |
| `report_mode` | string | `summary_diff`（默认）/ `timeline_events` / `snapshot` |
| `detail_limit` | int | 默认 `20`，范围 `1..200`；限制 `sample_changes` / `impacts` / `events` / `transitions` 等明细列表长度，保证单行 < 1 MiB |
| `max_entity_count` | int | 默认 `256`，范围 `1..512`；超出时按到 `origin` 的距离（无 origin 则按 x→y→z 字典序）取前 N，并置 `entity_count_truncated: true` |

并发上限：**同一桥接端最多 8 个活跃 watch 会话**（已关闭但处于报告保留期的会话不计）。达到上限 → `TOO_LARGE`，`message` 必须包含 `watch session limit reached`。

响应：

```json
{"id": 101, "ok": true, "data": {
  "watch_id": "explosion_test_01",
  "side": "server",
  "non_air_count": 1845,
  "entity_count": 3,
  "entity_count_truncated": false,
  "truncated_chunks": [],
  "seq": 0
}}
```

- `non_air_count`：基准快照（t0）中**已加载区块内**的非空气方块数（不受 filter 影响）。
- `entity_count`：基准快照中符合过滤条件、且被纳入追踪的实体数；超过 `max_entity_count` 时为上限值。
- `truncated_chunks`：因区块未加载而跳过的区块坐标列表，元素为 `[chunkX, chunkZ]`，去重升序；见 §7.10。
- `seq`：本会话事件序列号起点，恒为 `0`；此后每次规则触发 +1。
- `side`：采样权威侧，恒为 `"server"`（与 §3.9 `command.run` 的 `side` 语义一致；专用服务端实现同样返回 `"server"`）。

### 7.2 上传规则引擎

规则树是普通 JSON 对象；桥接端在服务端主线程按 tick 评估。所有规则对象都允许出现 `auto_stop`（只在**根规则**生效；子规则上的同名键忽略，保留前向兼容）。未知字段忽略（新增字段向后兼容），未知 `type` → `RULE_INVALID`。

#### 7.2.1 五类基础规则

| type | 核心参数 | 触发时机 |
|---|---|---|
| `after_seconds` / `after_ticks` | `seconds`（>0，≤3600）或 `ticks`（1..72000），二选一 | 从 `watch.start` 起精确等待；到期拍终态并与 t0 对比后上传。`auto_stop` 默认 `true` |
| `settle_quiet` | `quiet_ticks`（默认 20，1..1200）、`max_wait_seconds`（默认 30，1..300） | 连续 N 个 Tick 区域内无任何被跟踪变化后结算；到 max_wait 仍未静默则按 `timeout` 结算。`auto_stop` 默认 `true` |
| `on_condition` | `condition`（见下）、`timeout_seconds`（默认 30，0.1..300） | 条件首次满足时立即触发。`auto_stop` 默认 `true` |
| `interval` | `interval_ticks`（默认 20，1..1200）、`max_count`（可选，1..1000） | 每 N Tick 上报一次**自上次报告以来的增量**（首份报告对比 t0）。`auto_stop` 默认 `false` |
| `event` | `event`（当前仅 `"log.line"`）、`pattern`（可选正则，匹配整条日志 message）、`level_min`（默认 `INFO`）、`timeout_seconds`（默认 30） | `watch.start` 之后出现首条匹配日志时触发（不重放启动前历史）。`auto_stop` 默认 `true`；其他事件源 → `RULE_INVALID` |

`on_condition` 的 `condition.kind`：

| kind | 参数 | 触发定义 |
|---|---|---|
| `block_match` | `pos` int[3]、`to_block` string | 该坐标当前方块 id 等于 `to_block`，且 t0 基准不为该值（避免开局即触发） |
| `block_state` | `pos` int[3]、`property` string、`value` string | 该坐标该 BlockState 属性当前值等于 `value`，且与 t0 不同 |
| `entity_removed` | `entity_type` string（id / `*` 通配）、可选 `uuid` | 在观察窗口内被跟踪的该类型实体死亡 / 被移除（`removed`）。**离开区域不算**（`out_of_bounds` 只在报告里列出） |
| `entity_hp_below` | `threshold` number、可选 `uuid` / `entity_type` | 指定 UUID（或指定类型的任一被跟踪实体）血量低于阈值；非 LivingEntity 不参与 |

#### 7.2.2 组合规则

| type | 参数 | 语义 |
|---|---|---|
| `any` | `rules`（2..16）、`timeout_seconds`（默认 30） | 任一子规则先满足即立即上报并结束等待（“先命中即报，最多等 N 秒兜底”） |
| `all` | `rules`、`mode`：`latched`（默认）/ `simultaneous`、`timeout_seconds`（默认 30） | `latched`：所有子规则都曾满足过即触发；`simultaneous`：同一 Tick 同时满足才触发 |
| `sequence` | `steps`（2..16）、`timeout_seconds`（默认 30） | 有序串联：第 i 步满足后才激活第 i+1 步；最后一步完成才上报（基准始终是 t0） |
| `repeat` | `rule`（内层规则）、`max_count`（默认 1，1..100）、可选 `until`（内层规则）、`timeout_seconds`（默认 300，0.1..3600） | 内层规则每触发一次生成一份报告并重置内层状态，进入下一轮；达到 max_count 或 `until` 命中后结束会话。`auto_stop` 默认 `false`（但它按定义在轮次用尽时结束） |

约束（桥接端强校验，违反 → `RULE_INVALID`）：

- 最大嵌套深度 **4**（根规则计为 1；`any`/`all` 的 `rules`、`sequence` 的 `steps`、`repeat` 的 `rule`/`until` 各 +1）。
- 规则节点总数 **≤16**（含根；子规则数组的每一个元素都计数）。
- 未知 `type`、缺必填参数、参数类型/范围错误、正则编译失败 → `RULE_INVALID`。
- `seconds` 与 `ticks` 只能二选一；同时给或都不给 → `RULE_INVALID`。

#### 7.2.3 兜底与归因

- **超时兜底**：所有事件型 / 静默型 / 组合型规则都有超时上限；超时仍生成报告，`trigger_reason` 为 `"timeout"`，并附 `"timed_out": true` 与当前已采集的对比结果。
- **触发归因**：每份规则触发的报告必须带 `trigger_reason`（字符串，规范化路径）与 `trigger_trace`（字符串数组，按触发顺序，最多 32 条）。示例：
  - `"after_seconds(3.0)"`
  - `"on_condition(block_match@[0,64,0]->minecraft:air)"`
  - `"sequence[0]:on_condition(block_match@[0,64,0]->minecraft:air) -> sequence[1]:after_seconds(3.0)"`
  - `"any[1]:after_seconds(15.0)"`、`"all(latched)"`、`"interval(5)#3"`、`"timeout"`
- **自动清理**：`auto_stop: true` 的规则在上报后立即关闭会话（释放基准快照内存，报告保留 60s 供迟到消费，见 §7.9）；`interval` / `repeat` 默认 `auto_stop: false`，由 `watch.stop` 或空闲回收结束。
- **一次性 vs 持续**：`interval` / `repeat` 可产生多份报告（`seq` 递增）；其余规则至多产生一份。

### 7.3 报告模式

所有报告共用字段：

| 字段 | 说明 |
|---|---|
| `watch_id` / `seq` | 会话 id；本报告序号（从 1 开始，`watch.snapshot` 例外） |
| `report_mode` | 与 `watch.start` 一致 |
| `trigger_reason` / `trigger_trace` | §7.2.3；`watch.snapshot` 为 `"snapshot"` |
| `elapsed_ticks` | 从会话建立到本报告的服务端 tick 数 |
| `side` | `"server"` |
| `truncated` / `truncated_count` | 任一明细列表被截断时为 `true`，并给出被省略条目数；**禁止静默截断** |

#### 7.3.1 `summary_diff`（默认推荐）

只对比 t0 与 t1 两次采样（规则不要求逐 tick 变化检测时，桥接端无需每 tick 全量扫描）：

```json
{
  "block_summary": {
    "total_changed": 138,
    "affected_bounds": {"min": [-4, 60, -4], "max": [4, 67, 4]},
    "max_radius_from_origin": 4.24,
    "transitions": {"minecraft:stone -> minecraft:air": 96, "minecraft:dirt -> minecraft:air": 38},
    "sample_changes": [{"pos": [0, 63, 0], "from": "minecraft:stone", "to": "minecraft:air"}]
  },
  "entity_summary": {
    "affected_count": 2, "removed_count": 1, "entered_count": 0,
    "impacts": [
      {"uuid": "550e8400-e29b-41d4-a716-446655440000", "type": "minecraft:pig",
       "hp_before": 10.0, "hp_after": 0.0, "status": "removed"},
      {"uuid": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "type": "minecraft:zombie",
       "hp_before": 20.0, "hp_after": 6.5, "status": "alive"}
    ]
  }
}
```

- `affected_bounds`：所有变化方块坐标的包围盒；无变化为 `null`。
- `max_radius_from_origin`：变化方块中心到 `origin` 的最大欧氏距离；未给 origin 为 `null`。
- `transitions`：键为 `"<from> -> <to>"`，值为计数；默认按计数降序取前 `detail_limit` 条。
- `sample_changes`：前 `detail_limit` 条变化明细（按 x→y→z 字典序，保证可重复）。
- `entity_summary.impacts`：按 UUID 聚合；`status` 取值 `alive` / `removed` / `out_of_bounds` / `entered`。**实体转化（如僵尸村民治愈、猪被雷劈）必须同时列出旧 UUID 的 `removed` 与新 UUID 的 `entered` 两条，不许合并**。`removed` 且原为 LivingEntity 时 `hp_after` 为 `0.0`，否则为 `null`。
- `affected_count` = impacts 条数（含 removed/entered/out_of_bounds）。

#### 7.3.2 `timeline_events`

观察窗口内每个变化的先后顺序（带 tick 时间戳）：

```json
{
  "events": [
    {"tick": 41, "type": "block_changed", "pos": [0, 64, 0], "from": "minecraft:tnt", "to": "minecraft:air"},
    {"tick": 42, "type": "entity_hp", "uuid": "...", "entity_type": "minecraft:pig", "from": 10.0, "to": 6.5},
    {"tick": 43, "type": "entity_removed", "uuid": "...", "entity_type": "minecraft:pig", "reason": "removed"},
    {"tick": 44, "type": "entity_entered", "uuid": "...", "entity_type": "minecraft:item", "pos": [1.0, 64.0, 1.0]}
  ],
  "counts": {"block_changed": 96, "entity_hp": 2, "entity_removed": 1, "entity_entered": 1}
}
```

- 实体位移小于 `pos_epsilon`、血量变化小于 `hp_epsilon` 不记事件（默认降噪）；`entity_moved` 事件形如 `{"tick":..,"type":"entity_moved","uuid":..,"entity_type":..,"from":[x,y,z],"to":[x,y,z]}`。
- `events` 超过 `detail_limit` 时截断并置 `truncated: true`、`truncated_count`；`counts` 始终是完整计数。

#### 7.3.3 `snapshot`

触发那一刻区域内符合过滤条件的方块与实体精简列表（不做 diff）：

```json
{
  "blocks": [{"pos": [0, 64, 0], "id": "minecraft:chest", "properties": {"facing": "north"}}],
  "entities": [{"uuid": "...", "type": "minecraft:zombie", "pos": [1.0, 64.0, 1.0], "hp": 12.0, "alive": true}],
  "block_count": 1, "entity_count": 1
}
```

- `blocks` / `entities` 受 `detail_limit` 限制；被截断时 `truncated: true`、`truncated_count`。
- `properties` 是否输出由桥接端决定（参考实现始终输出；`include_nbt: true` 时额外给方块实体的 `nbt`）；消费端不得假设必有。

**体积守门**：报告是单行 JSON，必须满足 §1 的 1 MiB 上限。超预算时桥接端按 `detail_limit` 截断明细、置 `truncated: true` 与 `truncated_count`，禁止静默丢弃。

### 7.4 `watch.await` — 长轮询 / 游标拉取

请求：

```json
{"id": 102, "op": "watch.await",
 "params": {"watch_id": "explosion_test_01", "after_seq": 0, "wait_ms": 10000}}
```

| 字段 | 约束 / 默认 |
|---|---|
| `watch_id` | 必填；不存在 / 已过期 → `WATCH_NOT_FOUND` |
| `after_seq` | int ≥0，默认 `0`；只返回 `seq > after_seq` 的报告 |
| `wait_ms` | 默认 `10000`，范围 `1..60000`（与 §3.8 `log.wait` 一致） |

响应：

```json
{"matched": true, "next_seq": 1, "overflowed": false, "report": { ... }}
{"matched": false, "next_seq": 0, "overflowed": false}
```

- **游标语义**（与 §3.7 `log.history` 的 `afterId` 同构，但逐份消费）：`matched: true` 时 `next_seq` = 本次返回报告的 `seq`（**逐份前进，不跳过未读报告**）；`matched: false` 时 `next_seq` = 缓冲区当前最新 seq（无新报告时也前进，保证消费端不空转）；`0` 表示尚无任何报告。消费端把 `next_seq` 作为下一次的 `after_seq`，即可按顺序取走全部报告。
- 已关闭（auto_stop / `watch.stop` / 生命周期回收）且缓冲区中没有 `seq > after_seq` 的报告时，`watch.await` **立即返回** `matched: false`，不空等 `wait_ms`：已关闭会话不可能再产生报告，这是避免无意义阻塞的实现约定。
- 长轮询期间任一规则触发立即返回；无触发则等到 `wait_ms` 到期返回 `matched: false`（**这是正常分支，不是 `TIMEOUT` 错误**）。
- **缺口可见**：每个会话最多缓冲 64 份未消费报告；超过后淘汰最老的报告。若存在无法恢复的缺口（缓冲区最老 seq > `after_seq + 1`），响应置 `overflowed: true`——与 §3.7 的 `overflowed` 同款纪律，**不许静默丢报告**。
- 同一会话允许多个连接 / 多个并发 await；服务端不推进共享游标，游标由消费端自己保存。
- `after_seq` 为负 / 非整数 → `BAD_PARAMS`。
- auto_stop 关闭后的会话在 60s 报告保留期内仍可 await 取走剩余报告；过期 → `WATCH_NOT_FOUND`。
- 游标跨游戏重启无意义：消费端必须在每次收到 `hello` 后归零（§1）。

### 7.5 `watch.snapshot` — 立即出一次对比报告

参数：`watch_id`（必填）。响应 data：**报告对象本身**（与 §7.3 结构一致，`trigger_reason` 为 `"snapshot"`，`seq` 为当前最新序号但不 +1、不入缓冲）。

- 作用：不改变规则引擎状态（规则继续按原计划评估），也不阻塞 tick；采样在服务端主线程完成。
- 允许任意连接调用（只读）；不存在 → `WATCH_NOT_FOUND`。

### 7.6 `watch.list` — 活跃会话元数据

参数：无。

```json
{"watches": [
  {"watch_id": "machine_flow", "bounds": {"min": [-10,60,-10], "max": [10,80,10]},
   "dimension": "minecraft:overworld", "elapsed_ticks": 420, "rule_type": "interval",
   "report_mode": "summary_diff", "auto_stop": false,
   "buffered_reports": 3, "seq": 8, "phase": "running"}
]}
```

- 只列**活跃**会话；已关闭但处于报告保留期的会话不列（其报告仍可 await）。
- `phase`：`running` / `completed`（completed 仅出现在 stop 竞态窗口，通常不列）。
- `rule_type` 为根规则 `type`；`buffered_reports` 为当前缓冲区中的报告数（参考值，不等于未消费数）；`seq` 为最新报告序号。

### 7.7 `watch.stop` — 手动终止并释放快照

参数：`watch_id`（必填）。

```json
{"id": 105, "ok": true, "data": {"stopped": true, "final_report": { ... }}}
```

- **只有创建该会话的连接可以 stop**；其他连接 → `BAD_PARAMS`（`message` 含 `not the owner`）。其他连接只能通过 `watch.await` 按游标拉取。
- `final_report`：缓冲区中最高 `seq` 的报告（可能已被其他 await 取走），没有则为 `null`。
- 立即释放基准快照内存；报告保留 60s 供迟到 await 拉取。不存在 / 已过期 → `WATCH_NOT_FOUND`。
- 成功停止会向所有连接推送 `watch.stopped`（`reason: "manual"`）。

### 7.8 事件推送（best-effort）

规则触发 / 会话结束时，除唤醒 `watch.await` 外，还向所有已连接 TCP 客户端推送事件（§2.3 信封）：

```json
{"event": "watch.triggered", "data": {
  "watch_id": "explosion_test_01", "seq": 1,
  "trigger_reason": "after_seconds(3.0)",
  "report": { ... }
}}
{"event": "watch.stopped", "data": {"watch_id": "explosion_test_01", "reason": "auto_stop"}}
```

- `reason` 取值：`manual` / `auto_stop` / `dimension_unloaded` / `client_disconnected` / `idle_timeout` / `server_stopped` / `server_changed`。
- **背压红线继承 §2.3**：推送只在服务端主线程做一次非阻塞 `offer`；队列满即丢弃，丢弃数计入 `status.droppedEvents`（与 log 事件共用计数）。可靠获取渠道始终是 `watch.await` 的 seq 游标；事件流只是便利层。
- 事件不携带 `id`，不需要响应。

### 7.9 生命周期与资源回收

| 事件 | 行为 |
|---|---|
| 规则 `auto_stop: true` 上报后 | 立即关闭会话、释放基准快照；报告保留 **60s** 供迟到 await；推送 `watch.stopped(auto_stop)` |
| `watch.stop` | 立即关闭；保留报告 60s；推送 `watch.stopped(manual)` |
| 创建连接断开 | 该连接的会话进入 **60s 保护窗口**（无重连则清理全部所属会话；本协议无重连身份，按超时清理）。清理时推送 `watch.stopped(client_disconnected)` |
| 维度卸载 / 世界切换 | 停止该维度的全部会话并推送 `watch.stopped(dimension_unloaded)`；集成服退出 / 更换时全部清理（`server_stopped` / `server_changed`） |
| `interval` / `repeat` 空闲 | 超过 **300s** 没有任何 `watch.await` 引用（且无新报告产生）→ 强制 stop 并广播 `watch.stopped(idle_timeout)`，防止长会话内存泄漏 |
| 并发上限 | 活跃会话 ≤8；关闭但保留报告的会话不占名额 |

资源纪律：基准快照在关闭时立即释放；报告缓冲在保留期后释放；每个会话最多缓冲 64 份未消费报告（超出淘汰最老并让 `watch.await` 以 `overflowed: true` 暴露缺口）；单会话方块采样内存以 `32768` 格为硬上限，不允许无界增长。

### 7.10 线程纪律与区块加载保护（强制）

1. **只在服务端主线程读世界**：快照、比对、规则评估、报告生成必须在集成服 `server.execute(...)`（或专用服 `ServerTickEvents.END_SERVER_TICK`）里执行；生成的轻量 JSON 再交给网络 IO 线程发送。任何世界 / 实体 / 方块读取都不许在 IO 线程执行（继承 §6.1）。
2. **区块未加载保护**：扫描 / 比对前先用 `level.isLoaded(pos)` 或 `level.isLoaded(chunkX, chunkZ)` 判断目标区块；未加载直接跳过并记入 `truncated_chunks`，**禁止强制同步加载区块**。会话期间区块从卸载变加载时，下次采样自动纳入。
3. **采样节奏**：需要逐 tick 变化检测的规则（`settle_quiet`、`timeline_events`、`interval`）才逐 tick / 按 interval 全量采样；纯时间 / 条件规则只在 t0 与触发时各采样一次（`summary_diff` 的低开销路径）。具体实现可优化，但报告语义必须与本节一致。
4. **实体标识与边界**：
   - 三层标识：种类 id（`BuiltInRegistries.ENTITY_TYPE.getKey(type)`，统计归类用）、运行时数字 id（`entity.getId()`，仅单次会话辅助索引，**不作追踪主键**）、持久 UUID（`entity.getUUID()`，前后对比唯一主键）。
   - 实体转化（`convertTo`，如僵尸村民治愈、猪被雷劈）：必须按“旧 UUID `removed` + 新 UUID `entered`”两条记录呈现，不许合并。
   - 离开区域 → `out_of_bounds`；死亡 / 被清除 → `removed`；两者语义不同，不许混用。
   - 采样一律服务端权威（客户端只持有视距内实体且缺服务端 AI / 完整 NBT）；集成服与专用服的实现差异由报告 `side: "server"` 统一（同 §3.9）。
5. **响应唯一性**：`watch.start` / `watch.await` / `watch.snapshot` / `watch.list` / `watch.stop` 与其它 op 一样，每个请求恰好一个响应；异步路径必须复用响应去重机制（§2.2）。

### 7.11 错误码（本扩展新增）

| code | 语义 |
|---|---|
| `WATCH_NOT_FOUND` | watch_id 不存在 / 已停止且超过 60s 报告保留期 |
| `RULE_INVALID` | 规则树结构错误：嵌套深度 >4 / 节点数 >16 / 未知 type / 缺必填参数 / 参数越界 / 正则非法 / 不支持的事件源 |
| `TOO_LARGE` | 区域体积 >32768 格 / 活跃会话 >8 / 追踪实体数 >512 |
| `BAD_PARAMS` | watch_id 重复或含 `.` / min/max 维度不符 / 字段类型错误 / detail_limit 越界 / after_seq 非法 / 非 owner stop / 未知 report_mode |
| `TIMEOUT` | op 级看门狗超时（`watch.await` 无触发返回 `matched:false`，**不是** TIMEOUT） |
| `WORLD_NOT_LOADED` | 维度不存在 / 世界未加载 / 参考客户端桥接无集成服务端 |
| `INTERNAL` | 桥接端内部异常（message 带类名，便于排障） |

错误码总表见 §5；旧错误码（`BAD_OP`、`CLIENT_ONLY`、`INTERNAL`）语义不变。

### 7.12 完整示例：3 秒后上传的爆炸范围测试

```text
1) watch.start（围住 TNT，t0 基准快照）
2) command.run: summon minecraft:tnt 0 64 0 {Fuse:10}
3) watch.await(after_seq=0, wait_ms=15000)
```

```json
{"id": 202, "ok": true, "data": {"matched": true, "next_seq": 1, "report": {
  "watch_id": "explosion_test_01", "seq": 1, "report_mode": "summary_diff",
  "trigger_reason": "after_seconds(3.0)", "trigger_trace": ["after_seconds(3.0)"],
  "elapsed_ticks": 60, "side": "server", "truncated": false,
  "block_summary": {
    "total_changed": 138,
    "affected_bounds": {"min": [-4, 60, -4], "max": [4, 67, 4]},
    "max_radius_from_origin": 4.24,
    "transitions": {"minecraft:stone -> minecraft:air": 96, "minecraft:dirt -> minecraft:air": 38},
    "sample_changes": [{"pos": [0, 63, 0], "from": "minecraft:stone", "to": "minecraft:air"}]
  },
  "entity_summary": {"affected_count": 2, "removed_count": 1, "entered_count": 0, "impacts": [
    {"uuid": "550e8400-e29b-41d4-a716-446655440000", "type": "minecraft:pig",
     "hp_before": 10.0, "hp_after": 0.0, "status": "removed"},
    {"uuid": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "type": "minecraft:zombie",
     "hp_before": 20.0, "hp_after": 6.5, "status": "alive"}
  ]}
}}}
```

### 7.13 组合规则速查

- **特定坐标方块被破坏**：`{"type":"on_condition","condition":{"kind":"block_match","pos":[12,65,-4],"to_block":"minecraft:air"},"timeout_seconds":15,"auto_stop":true}`
- **未知耗时的连锁反应**：`{"type":"settle_quiet","quiet_ticks":20,"max_wait_seconds":15,"auto_stop":true}`
- **串联 + 兜底**：`{"type":"any","rules":[{"type":"sequence","steps":[{"type":"on_condition","condition":{"kind":"block_match","pos":[0,64,0],"to_block":"minecraft:air"}},{"type":"after_seconds","seconds":3.0}]},{"type":"after_seconds","seconds":15.0}],"auto_stop":true}`
- **多条件与**：`{"type":"all","mode":"latched","rules":[{"type":"on_condition","condition":{"kind":"block_state","pos":[5,64,5],"property":"lit","value":"true"}},{"type":"on_condition","condition":{"kind":"entity_removed","entity_type":"mymod:test_dummy"}}],"timeout_seconds":20,"auto_stop":true}`
- **机器流水线**：`{"type":"interval","interval_ticks":5,"max_count":100,"auto_stop":false}`，配合 `watch.snapshot` 随时取当前态。
