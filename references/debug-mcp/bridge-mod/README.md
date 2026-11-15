# mc-debug-bridge — 极简调试桥（Fabric 1.21.11 参考实现）

> 适用环境：MC 1.21.11 · Fabric · Mojmap · JDK 21 · Loom 1.17.21（组织已验证坐标，见 `../../../environments/versions/1.21.11/README.md`）
> 协议：[MCB v1](../protocol.md)
> 验证方式：`gradle build` 编译通过（编译探针）；运行时行为验证记录在版本卡 `bridge.md`。

这是 MCB v1 协议的**首个参考实现**。它存在的意义不是"装上就能用"（当然也能用），而是**作为模板**：任何 AI 拿着协议规范 + [PORTING.md](PORTING.md)，就应该能为其他 Loader / 其他版本写出等价物。

## 设计取舍

- **零第三方依赖**：连 Fabric API 都不依赖——只用 loader 自带 client entrypoint、原版类、MC 自带的 Gson 与 log4j2。移植时唯一的依赖问题就是"没有依赖问题"。
- **单侧 client**：单人游戏（集成服务端）场景下覆盖协议全部能力；`command.run` 在纯多人客户端场景按协议 §3.9 返回错误而非降级（v1 从简）。
- **代码即文档**：每个文件头注明对应的协议章节；每个"容易写错"的点（线程纪律、响应唯一性、缺口可见性）都在注释里标了协议出处。

## 文件地图

| 文件 | 行数级 | 对应协议章节 | 职责 |
|---|---|---|---|
| `McbMod.java` | ~55 | §1 | 入口：装日志捕获、开 TCP 服务端、解析端口 |
| `McbLogCapture.java` | ~180 | §3.7 / §3.8 / §2.3(log) | log4j2 Appender + 环形缓冲 + 增量读 + 订阅广播 |
| `McbServer.java` | ~330 | §1 / §2 / §3 分发 | TCP accept / NDJSON 读写 / 主线程跳转 / 响应唯一性 |
| `McbHandlers.java` | ~330 | §3.2–§3.6 / §3.9 / §3.10 | 世界状态读取与命令执行（全部假设在游戏主线程） |
| `McbScreenshot.java` | ~40 | §3.11 | 帧缓冲 → PNG base64（渲染线程内完成像素读取） |

## 构建与安装

```bash
cd references/debug-mcp/bridge-mod
./gradlew build          # 需要 Gradle 9.5.1（见版本卡）；wrapper 未入库，可 `gradle wrapper` 生成
# 产物: build/libs/mc-debug-bridge-0.1.0.jar
```

把 jar 放进游戏实例的 `mods/` 文件夹即可。游戏启动后桥接监听 `127.0.0.1:25585`；改端口用 JVM 参数 `-Dmcb.port=NNNN`。

联调可用任何 MCP 客户端 + 本目录同级 `mcp-server/`（协议参考消费端）。

## 红线（改这个模板前必读）

1. **只监听 127.0.0.1**——`McbServer.start` 里的 bind 地址不许改，这个服务端没有鉴权。
2. **响应唯一性**——`command.run` 有超时看门狗，靠 `responded` 集合保证一个请求只回一个响应（协议 §2.2）。新增长耗时 op 时必须复用这个机制。
3. **主线程纪律**——任何 `level.getBlockState` 之类调用只允许出现在 `Minecraft.execute()` 里。在 TCP 线程读世界 = 随机崩溃。
4. **缺口可见性**——日志环形缓冲丢条目时必须置 `overflowed`，不许静默。
