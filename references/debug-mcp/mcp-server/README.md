# mc-debug-mcp — MCB v1 消费端参考实现

> 环境无关：只认 [MCB v1 协议](../protocol.md)，不知道对面是 Fabric 还是 NeoForge。
> 验证方式：`npm run build` + `node smoke.mjs`（离线契约）+ `node integration.mjs`（假桥透传）；运行时联调记录在版本卡 `bridge.md`。

## 快速开始

```bash
cd references/debug-mcp/mcp-server
npm install && npm run build
# MCP 客户端配置：
# { "mcpServers": { "minecraft": {
#     "command": "node", "args": ["/绝对路径/mcp-server/dist/index.js"] } } }
```

前提：游戏端已装 `bridge-mod/` 构建出的桥接 jar 且已进入（或正在加载）世界。端口不同时给 MCP 进程加环境变量 `MCB_PORT`。

## 工具 ↔ op 映射

工具与 op 一一对应（协议 §3 / §7 / §8），工具不额外发明抽象；标 **0.2.1+** / **0.3.0+** / **0.4.0+** 的工具分别需要 bridge-mod 0.2.1 / 0.3.0 / 0.5.0（协议 v0.5.4）及以上，旧桥会对未知 op 回 `BAD_OP`（消费端应降级为轮询 / 手工建世界，见协议 §7、§8 头注）。

| MCP 工具 | op | 说明 |
|---|---|---|
| `mc_status` | `status` | 综合状态（永远可查） |
| `mc_player` | `player.info` | 玩家状态与快捷栏 |
| `mc_block_get` | `block.get` | 单方块（含方块实体 NBT） |
| `mc_scan_blocks` | `block.scan` | 区域扫描（可按 id/`#tag` 过滤） |
| `mc_map` | `map.ascii` | ASCII 空间地图（top 俯视 / slice 切片） |
| `mc_entities` | `entity.list` | 实体列表 |
| `mc_logs` | `log.history` | 增量日志（游标 `afterId` → `nextId`） |
| `mc_wait_log` | `log.wait` | 长轮询等一条日志 |
| `mc_command` | `command.run` | 执行命令；`side` 选 server/client（见下） |
| `mc_screenshot` | `screenshot.take` | 截图（base64 image content，多模态可看） |
| `mc_screen_current` | `screen.current` | 当前 Screen / 窗口焦点 / 失焦暂停开关 **0.2.1+** |
| `mc_client_screenshot` | `client.screenshot` | 截图落盘到 `<gameDir>/screenshots/` 并回读为 image content **0.2.1+** |
| `mc_pause_on_lost_focus` | `client.pause_on_lost_focus` | 读写原版 `pauseOnLostFocus`（缺省只读）**0.2.1+** |
| `mc_reload_resources` | `client.reload_resources` | 客户端资源包重载（F3+T 等价）**0.2.1+** |
| `mc_watch_start` | `watch.start` | 建立规则驱动观察会话与 t0 基准快照 **0.3.0+** |
| `mc_watch_await` | `watch.await` | 长轮询 / 游标拉取报告（`after_seq` → `next_seq`）**0.3.0+** |
| `mc_watch_snapshot` | `watch.snapshot` | 立即出一次对比报告（不改规则状态、不入缓冲）**0.3.0+** |
| `mc_watch_list` | `watch.list` | 活跃会话元数据（无参数）**0.3.0+** |
| `mc_watch_stop` | `watch.stop` | 手动终止并取 `final_report`（只有 owner 能停）**0.3.0+** |
| `mc_world_create` | `world.create` | 按预设拉起受控测试世界：默认创造 / 关结构 / 关自然刷怪 / normal / 锁正午晴天（协议 v0.5.4 新增）**0.4.0+** |
| `mc_world_delete` | `world.delete` | 安全删除测试存档：仅 `mcb_` 前缀或 world.create 标记，玩家手工世界永不可删（协议 v0.5.4 新增）**0.4.0+** |
| `mc_move_player` | `player.move` | 带地面安全的传送（虚空自动垫玻璃） |
| `mc_camera_eye` | `camera.eye` | 天眼一次性取景截图（不移动玩家） |
| `mc_camera_set` | `camera.set` | 进入持久自由相机 |
| `mc_camera_look` | `camera.look` | 天眼视角射线查询方块 |
| `mc_camera_clear` | `camera.clear` | 退出自由相机 |

### `mc_command` 的 `side`

`side` 可选 `auto`（默认）/ `server` / `client`：

- `auto`：有集成服（单人存档）走 `server`，否则有连接走 `client`，都没有则 `WORLD_NOT_LOADED`（= 0.1.x 的旧行为）。
- `server`：集成服主线程以四级权限执行，能拿到 `returnValue` 与命令反馈。
- `client`：走客户端命令通道 `ClientPacketListener.sendCommand`，多人世界也能用，但回显通常捕获不到（`resultText:""`、`returnValue:null`）。
- 响应里的 `side` 是**实际执行**的那一侧（`auto` 会被解析成 `server`/`client`）；不传 `side` 时消费端**不发送该字段**，由桥接端套默认值——这样与不认这个字段的 0.1.x 桥保持字节级兼容。

### Watch 会话（`mc_watch_*`）

用于爆炸范围、连锁破坏、实体 AI / 伤害这类**有观察窗口**的实验：先立 t0 基准快照，把“什么时候发、发什么粒度”交给桥接端，命中后一次性回传 t1 报告（协议 §7）。

```text
1) mc_watch_start  { watch_id, min, max, upload_rule, report_mode? ... }  → t0 快照 + seq:0
2) mc_command      { command: "summon minecraft:tnt 0 64 0 {Fuse:10}" }  → 制造变化
3) mc_watch_await  { watch_id, after_seq: 0, wait_ms: 15000 }            → matched + report
4) mc_watch_stop   { watch_id }                                           → stopped + final_report
```

- **规则驱动**：`upload_rule` 是普通 JSON 规则树（`after_seconds` / `settle_quiet` / `on_condition` / `interval` / `event` / `any` / `all` / `sequence` / `repeat`），由桥接端解释，消费端**原样透传**、不重复实现规则语义（`tracks` 同理）。规则树错误由桥接端回 `RULE_INVALID`。
- **t0 → t1**：`mc_watch_start` 返回的 `seq` 是事件序列号起点（恒 `0`）；命中报告带 `trigger_reason` / `trigger_trace`，`auto_stop: true` 的规则上报后立即关会话（报告保留 60s 供迟到 await）。
- **游标纪律**：`mc_watch_await` 用上次返回的 `next_seq` 作为下一次的 `after_seq`（首次省略 = 0）；`matched:false` 是**正常超时分支**，不是 `TIMEOUT` 错误；`overflowed:true` 表示报告已被淘汰出现缺口，不可恢复。`next_seq` 跨游戏重启无意义（每次 `hello` 后归零）。
- **谁能 stop**：只有创建会话的连接能 `mc_watch_stop`，其他连接 → `BAD_PARAMS`（`message` 含 `not the owner`），只能通过 `mc_watch_await` 按游标拉取。
- **客户端桥接限制**：参考 Fabric 客户端桥接只在**集成服务端**（单人存档）可用；多人世界 / 专用服务端未集成时返回 `WORLD_NOT_LOADED`，这不是协议限制。同一桥接端最多 8 个活跃会话。
- 省略的可选字段（`origin` / `dimension` / `report_mode` / `detail_limit` / `max_entity_count` / `after_seq` / `wait_ms`）不会在消费端补默认值，由桥接端套 §7.1 / §7.4 的默认值。

### 测试世界（`mc_world_*`，协议 §8 / v0.5.4）

用一条 `mc_world_create` 拉起**可控测试世界**，测试结束 `mc_world_delete` 清理，避免「手工建世界 → 进错生存世界被怪打死 → 村庄/动物/天气干扰观察结果」。

```text
1) mc_world_create { name: "mcb_test", preset: "flat_clean" }   → 建图（默认创造/关结构/关自然刷怪）
2) mc_status                                                     → worldLoaded / timeOfDay / gamemode 复查
3) ... 用 mc_command / mc_watch_* 做测试 ...
4) mc_world_delete  { name: "mcb_test", force: true }            → 收尾删除（force：正在运行则先安全退回主菜单）
```

- **默认值写死在工具 schema**（协议 §8.0）：只传 `name` + `preset` 就会自动带上 `clean_if_exists=true` / `game_mode="creative"` / `generate_structures=false` / `do_mob_spawning=false` / `difficulty="normal"` / `lock_time=6000` / `lock_weather="clear"` / `fire_tick=false` / `keep_inventory=true` / `allow_commands=true` / `timeout_ms=120000`；`seed` **不在消费端补默认值**（由桥接端给 12345）。
- **硬原则**：除非本次明确测试生存机制或结构生成，否则保持默认创造模式与关闭结构；默认 `normal` 难度 + 关自然刷怪（**不要用和平模式**——和平难度下 `/summon` 的敌对测试怪会立即消失，战斗/伤害/仇恨测试做不了）。所有默认都可显式覆盖（`game_mode:"survival"`、`generate_structures:true`、`lock_time:null`…），但只有本次测试确实需要时才覆盖。
- **预设**：`bedrock_lab`（超平坦基岩实验室：方块/红石/机器/爆炸范围）/ `flat_clean`（纯净超平坦：生物 AI、作物）/ `vanilla_quiet`（低噪原版世界，seed 12345：真实群系/矿脉）/ `void_platform`（虚空 + 9×9 基岩平台：飞行实体、粒子、干净截图）。
- **超时**：建图是异步长操作（进世界 + 应用规则后才响应，可能 1–2 分钟），`timeout_ms` 范围 30000–600000（默认 120000）；消费端请求超时比桥接看门狗多留余量，让桥接的 `TIMEOUT` 契约先于本地掐断生效（同截图 / 热重载的看门狗纪律）。
- **错误码原样透传为 `[MCB <code>]`**：`PRESET_INVALID` / `WORLD_ALREADY_EXISTS`（`clean_if_exists=false` 且存档已存在）/ `WORLD_IN_USE`（世界正在运行，或另一个 world op 在飞——协议 §8.7 单飞）/ `WORLD_NOT_FOUND` / `WORLD_DELETE_FORBIDDEN`（**安全红线**：只允许删 `mcb_` 前缀或 world.create 写入标记的存档；玩家手工世界永远删不了，`force` 也不能绕过）/ `TIMEOUT` / `INTERNAL`。
- 与 op 一一对应，无第二套抽象；`world.progress` 事件属 best-effort 便利层，MCP 消费端目前不消费事件流（响应唯一性由 §2.2 保证）。

## 测试

```bash
npm run build && node smoke.mjs && node integration.mjs
```

- `smoke.mjs`（默认离线）：不需要游戏。它给 MCP 进程指定一个**刚被释放的空闲端口**（不会去碰默认 25585 上可能正在跑的游戏），断言 initialize、26 个工具（含 5 个 `mc_watch_*` 与 2 个 `mc_world_*`）、死桥 `BRIDGE_UNREACHABLE` 契约（普通工具 + `mc_watch_list` + 两个 world 工具）与 zod 参数校验（如非整数 `wait_ms`、非法 `preset` / `game_mode` 在消费端被拒、不上线）；另直接 parse 工具的 zod schema，断言协议 §8.0 的世界默认值齐全（离线可验，不依赖游戏）。
- `integration.mjs`：自己起一个**假桥**（随机端口 25586+），逐 op 严格校验线上参数形状（扁平 x/y/z、截图文件名只能是 basename、`hide_hud` 布尔、`side` 枚举……）并断言 4 个 0.2.1 工具与 `mc_command` 的透传。watch 场景覆盖完整剧本 **start（爆炸配置）→ await（matched，报告原样透传）→ stop（final_report）**，另断言 `tracks` / `upload_rule`（含未知字段）原样到达、`after_seq` / `wait_ms` 为整数且范围正确、`matched:false` + `next_seq` 游标分支、snapshot/list/stop 的 wire shape，以及省略的可选字段不被补默认值。world 场景在独立模块 `world-ops.mjs`（假桥 handler + 断言 + LIVE 路径，拆文件以控制在 1000 行内）：校验 §8.0 默认值全部落到线上参数、`timeout_ms → timeoutMs` 字段名映射、`null` 锁定值、`seed` 不被补默认、非法存档名消费端拒绝，以及 §8.4 安全红线（`WORLD_DELETE_FORBIDDEN`）错误码透传。不需要游戏；`MCB_LIVE=1 node integration.mjs` 会额外跑真机的 world 建/删路径（默认跳过）。
- `smoke.mjs` 的**活桥模式**（运行时验收，需要游戏 + bridge-mod 0.2.1+）：

  ```bash
  MCB_LIVE=1 node smoke.mjs            # 默认连 127.0.0.1:25585
  MCB_LIVE=1 MCB_PORT=25600 node smoke.mjs
  ```

  活桥模式先裸 TCP `ping`，然后断言 `mc_screen_current` 的 `pause_on_lost_focus === false`（v0.5.1 失焦保活）、`mc_command time set noon` 的 `success === true`、以及 `mc_client_screenshot(filename="mcb_smoke.png")` 返回 image content 且文件存在非空；最后按 **mc_world_create → mc_status → mc_world_delete** 顺序验收协议 §8 世界闭环（预设 `flat_clean`、断言 §8.0 默认值确实生效、删图后 `mc_status` 回到未加载）。桥接端版本低于 0.5.0（不认 `world.*`）时回 `BAD_OP`，该段自动跳过而不是失败。不设 `MCB_LIVE` 时完全不连游戏。

## 实现要点（换语言重写时对照）

- **懒连接 + 自愈重连**：首次请求才连，游戏重启后下一次请求自动重连。`hello` 握手等待 3 秒超时。
- **请求响应按 id 匹配**：响应可能乱序（长轮询并发），靠 `pending` 表匹配；超时即从表中删除。
- **错误透传**：桥接的 `error.code` 包装成 `[MCB <code>]` 前缀文本返回给模型——模型能看懂 `WORLD_NOT_LOADED` 该去创建世界，看懂 `BRIDGE_UNREACHABLE` 该去启动游戏。
- **游标纪律**：`mc_logs` 的 `afterId` 与 `mc_watch_await` 的 `after_seq` 都由模型自己持有（工具描述里写明了增量协议），`overflowed: true` 会原样透传——缺口必须可见；`watch.await` 的请求超时取 `wait_ms`（缺省 10000）+ 15000 余量，让桥接端的 `TIMEOUT` / `matched:false` 契约先于消费端掐断生效。
- **落盘截图回读**：`mc_client_screenshot` 拿到的 `path` 由 MCP 进程直接 `readFile` 后内联成 image content（§4），读不到时降级为 text + warning；桥接端只负责把文件写完再响应，不在协议里传 base64，避免大消息顶到 1 MiB 单行上限。
- **看门狗留余量**：消费端请求超时总是大于桥接端看门狗（截图 10s → 20s，资源重载 30s → 40s），这样超时错误由桥接端以 `TIMEOUT` 契约返回，而不是被客户端先行掐断。
- 本 v1 实现未消费 `log` 推送事件（工具直接走 `log.history`/`log.wait` op）；事件流留给未来的 live-tail 工具，协议兼容性由 §4 版本化规则保证。
