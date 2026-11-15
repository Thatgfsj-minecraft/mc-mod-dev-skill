# mc-debug-mcp — MCB v1 消费端参考实现

> 环境无关：只认 [MCB v1 协议](../protocol.md)，不知道对面是 Fabric 还是 NeoForge。
> 验证方式：`npm run build` + stdio smoke test；运行时联调记录在版本卡 `bridge.md`。

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
| `mc_command` | `command.run` | 四级权限执行命令 |
| `mc_screenshot` | `screenshot.take` | 截图（image content，多模态可看） |

## 实现要点（换语言重写时对照）

- **懒连接 + 自愈重连**：首次请求才连，游戏重启后下一次请求自动重连。`hello` 握手等待 3 秒超时。
- **请求响应按 id 匹配**：响应可能乱序（长轮询并发），靠 `pending` 表匹配；超时即从表中删除。
- **错误透传**：桥接的 `error.code` 包装成 `[MCB <code>]` 前缀文本返回给模型——模型能看懂 `WORLD_NOT_LOADED` 该去创建世界，看懂 `BRIDGE_UNREACHABLE` 该去启动游戏。
- **游标纪律**：`mc_logs` 的 `afterId` 由模型自己持有（工具描述里写明了增量协议），`overflowed: true` 会原样透传——缺口必须可见。
- 本 v1 实现未消费 `log` 推送事件（工具直接走 `log.history`/`log.wait` op）；事件流留给未来的 live-tail 工具，协议兼容性由 §4 版本化规则保证。
