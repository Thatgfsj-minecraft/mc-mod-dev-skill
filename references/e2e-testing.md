# E2E 测试体系：专用服务器 + mineflayer + RCON

原型：handy-shulkers-test（node + mineflayer 4.39.0 + minecraft-data 3.117.0，`type: commonjs`）。

## 架构分工

| 角色 | 职责 | 明确不做 |
|---|---|---|
| mineflayer 机器人 | join、lookAt（设朝向）、activateItem（右键空气/物品）、activateBlock（右键方块） | **不做任何状态断言**（1.21.x 客户端解析有 PartialReadError 缺陷） |
| RCON（零依赖手写客户端） | `/item replace` 塞物品、`/data get entity` 读 NBT、`/execute if block` 方块断言、`/time set`、`/difficulty` | — |
| 专用服务器 | 唯一真相来源；服务器日志零异常 = 服侧无 bug 的必要条件 | — |

## 服务器搭建（一次性）

1. 从 fabric 官网 meta 拉 `fabric-server.jar`（安装器，首跑自动下载装好 loader）。
2. `mods/`：`fabric-api.jar` + 本模组 jar。
3. `server.properties` 关键项：
   ```properties
   online-mode=false
   enable-rcon=true
   rcon.password=testpass
   rcon.port=25575
   level-type=minecraft\:flat
   spawn-protection=0
   ```
4. `eula.txt`：`eula=true`。给 Bot 发 op（level 4）可让 chat 命令兜底。
5. 多套并行：RCON 端口错开（1.21.1 用 25575，1.21.11 用 25576）。

## 运行序列

```bash
cd server-dir && java -Xmx2G -jar fabric-server.jar nogui   # 注意：必须在 server 目录里跑
# 等日志 "Done" 与 "[modid] SELF-TEST PASS"（模组启动自检）
node e2e.js                                                  # 结束时自动 /stop，退出码=失败数
```

RCON 大响应注意：首包 ≥4000 字节时要发空命令取结束标记排空（rcon.js 里 `command()` 已处理）。

## 断言模式（经过实战校正）

**可靠：**
- `/data get entity Bot SleepTimer` → 内联返回 `entity data: 18s`（数值+NBT 类型后缀 `s`=short；`f`=float、`d`=double）。**注意**：查具体路径时不回显字段名，匹配数值别匹配字段名。
- `/data get entity Bot Pos` → 解析 `[x d, y d, z d]` 后 `Math.floor`（注意负数 floor！-4.5 → -5）得方块坐标。
- 裸方块断言：`/execute if block X Y Z minecraft:red_bed[facing=east,part=foot]` → 返回内联 `Test passed` / `Test failed`。tag 形式 `#minecraft:beds` 同样支持。
- 物品探针法（验证菜单真开在服务端）：向 `container.<slot>` `/item replace` 一个钻石 → 菜单开着时它**不在**玩家 Inventory 里、关闭后回到 Inventory。

**不可靠（踩过坑）：**
- ❌ `execute if block ... run say X`：say 输出走聊天广播，**RCON 响应为空串**，无论条件真假——所有这类断言恒假。
- ❌ 任何依赖 mineflayer `bot.currentWindow`/物品栏客户端态的断言：1.21.x PartialReadError（ArmorTrimMaterial 解析 desync），时好时坏。

**探针自检**：写任何方块断言前，先对已知方块验证探针本身（超平坦 0 -64 0 必是 bedrock）：
```js
'/execute if block 0 -64 0 minecraft:bedrock'  // 必须返回 "Test passed"
'/execute if block 0 -64 0 minecraft:stone'    // 必须返回 "Test failed"
```

## 时序要点

- 睡觉判定：`SleepTimer` > 0 即在睡（900ms ≈ 18 ticks）；**床方块存在性是"真在睡"的更强断言**（原版每 tick 校验床存在，无床会瞬时醒来）。
- 醒来/清理断言要留缓冲（≥1.2s）：天亮唤醒 → 下一服务端 tick 才删床。
- 测试睡觉前 `/difficulty peaceful` + `/kill @e[type=!minecraft:player]`，防怪物触发 `NOT_SAFE`；`/time set midnight` 保证夜晚条件。
- 单人服务器全员入睡会自动跳过夜晚——每轮测试开头重新 `/time set midnight`。

## 覆盖清单模板（来自 handy-shulkers）

1. 触发条件：夜晚可睡/白天拒绝（SleepTimer）
2. 持久性：睡后 1.5s 仍在睡（无瞬时醒）
3. 手持物品不被消耗
4. 唤醒 + 临时物清理干净（方块消失）
5. 每类界面：打开 → 服务端探针证明菜单持有物品 → 关闭归还
6. 写入持久：容器内放物品 → 关闭 → `/data get entity Bot HandItems[0]` 里组件包含该物品
7. 读入安全：预装内容的物品开关后内容不丢
8. 优先级：瞄准有菜单的方块时原版行为胜出（title JSON 判定窗口类型）
9. 方向类行为（床朝向）：四方向循环断言方块 facing/part
