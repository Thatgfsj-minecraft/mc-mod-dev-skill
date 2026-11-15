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
3. **游标纪律**——`mc_logs` 的 `afterId` 自己持有；看到 `overflowed: true` 说明有缺口，回 `afterId=0` 重看或直接查崩溃报告。
4. **空间感知三段式**——`mc_map`（看布局）→ `mc_scan`（定位关键方块）→ `mc_block_get`（精查状态/NBT）。直接对大区域硬扫是浪费上下文。
5. **命令失败要读回执**——`mc_command` 返回 `resultText`（命令反馈原文），setblock 打错坐标、give 配错 id 都会在这里显形，不要假设成功。
6. **修改场景用一次性世界**——命令是四级权限，`kill @e` / 大范围 fill 都执行得出去；在专用测试存档里玩（与 e2e-testing.md 的世界纪律一致）。

## 5. 为新环境写桥接（扩展流程）

1. 读 `debug-mcp/protocol.md`（契约）+ `debug-mcp/bridge-mod/PORTING.md`（移植五步 + 历史易变调用点清单）。
2. 按 SKILL.md §7 对清单逐个核实 API，实现桥接（传输层基本原样搬，世界 API 按目标映射重写）。
3. 验证：编译通过 + 行为测试（至少覆盖 `ping`/`status`/`block.get`/`log.history`/`command.run` 五条主路径）。
4. 按 `references/README.md` 沉淀：目标版本卡目录新建 `bridge.md`（写明验证方式），更新覆盖表，实现放进 `debug-mcp/bridge-mod-<loader>-<版本>/`。
