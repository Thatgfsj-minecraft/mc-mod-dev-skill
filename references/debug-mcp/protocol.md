# MCB v1 — Minecraft 调试桥线协议（Minecraft Console Bridge, version 1）

> 适用环境：环境无关（任何 Loader / 任何版本都可实现本协议）。
> 验证方式：本文是协议规范（契约层）；各环境的实现要点见 `bridge-mod/`（Fabric 1.21.11 参考实现）与对应版本卡的 `bridge.md`。
> 设计目标：**零依赖可实现**——一个只学过 JDK 标准库和目标环境世界 API 的 AI，拿着本文就应该能写出桥接端。

## 0. 设计原则

1. **零依赖传输**：TCP + 每行一个 JSON（NDJSON）。不要求 WebSocket / HTTP 库，JDK `ServerSocket` 即可实现，Node `net.connect` 即可消费。
2. **单向职责**：桥接端（游戏内）只做"读世界 + 抓日志 + 跑命令 + 截图"四件事；一切协议编解码、游标管理、重连逻辑都在 MCP Server（消费端）。
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

## 3. Op 全集

命名约定：`域.动作`，全小写。工具实现（MCP Server）应与 op 一一对应，不引入 op 之外的第二套抽象。

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

参数：`command`（字符串，不带前导 `/`）；可选 `timeoutMs`（默认 10000）。

响应 data：`{"success": true, "resultText": "Set the time to 1000", "feedback": []}`

执行语义（桥接端按序降级）：

1. 存在集成服务端（单人存档）→ 在服务端主线程以**四级权限**执行，收集命令反馈。
2. 无集成服务端但客户端已进服务器世界 → 作为**聊天消息**发送（原样带 `/`），此时反馈不可捕获，`resultText` 为空并在 message 里说明。
3. 两者皆无 → `WORLD_NOT_LOADED`。

- 命令执行必须有超时保护，防止 `kill @e` 之类命令挂死桥接。

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

## 4. 版本化

- `protocol` 主版本号出现在 `hello` 中。**新增 op、新增可选参数、新增响应字段 = 向后兼容，主版本号不变**。
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

## 6. 实现者的线程纪律（规范要求，两种实现都适用）

1. TCP 接收/发送在独立 IO 线程；**任何世界/实体/方块读取必须投递到游戏主线程**执行后回填响应（游戏 API 不是线程安全的，这条纪律违反了会产生随机崩溃，而且是本协议所有实现里最难查的坑）。
2. 响应顺序不作保证——桥接端允许并发处理长轮询；消费端按 `id` 匹配。
3. 桥接端崩溃（`INTERNAL`）不得关闭 TCP 连接；只有游戏退出才断连。
