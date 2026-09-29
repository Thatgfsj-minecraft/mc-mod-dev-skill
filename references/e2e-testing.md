# E2E 测试体系：专用服务器 + mineflayer + RCON

适用于任何有玩家交互行为的模组：机器人负责"像玩家一样操作"，服务端 RCON 负责"权威判定结果"。技术栈：node + mineflayer + minecraft-data + 零依赖手写 RCON 客户端。

## 架构分工

| 角色 | 职责 | 明确不做 |
|---|---|---|
| mineflayer 机器人 | join、lookAt（设朝向）、activateItem（右键空气/物品）、activateBlock（右键方块） | **不做任何状态断言**（1.21.x 客户端解析有 PartialReadError 缺陷） |
| RCON | `/item replace` 塞物品、`/data get entity` 读 NBT、`/execute if block` 方块断言、`/time set`、`/difficulty` | — |
| 专用服务器 | 唯一真相来源；服务器日志零异常 = 服侧无 bug 的必要条件 | — |

## 服务器搭建（一次性）

1. Fabric 官网 meta 拉 `fabric-server.jar`（安装器，首跑自动装好 loader）。
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

4. `eula.txt`：`eula=true`；给 Bot 发 op（level 4）可让 chat 命令兜底。
5. 多套并行：RCON 端口错开（25575/25576...）。

## 运行序列

```bash
cd server-dir && java -Xmx2G -jar fabric-server.jar nogui   # 必须在 server 目录里跑
# 等日志 "Done" 与模组启动自检输出（建议模组带 SELF-TEST）
node e2e.js                                                  # 结束时自动 /stop，退出码=失败数
```

RCON 大响应注意：首包 ≥4000 字节时要发空命令取结束标记排空。

## 断言模式（经过实战校正）

**可靠：**

- `/data get entity Bot <路径>` → 内联返回 `entity data: <值><类型后缀>`（`s`=short、`f`=float、`d`=double）。**注意**：查具体路径时不回显字段名，匹配数值别匹配字段名。
- `/data get entity Bot Pos` → 解析 `[x d, y d, z d]` 后取方块坐标。**注意负数**：`Math.floor(-4.5) = -5`，实体坐标转方块坐标必须用 floor。
- 裸方块断言：`/execute if block X Y Z <方块id>[<属性>=<值>,...]` → 返回内联 `Test passed` / `Test failed`。tag 形式 `#minecraft:<tag>` 同样支持。
- 物品探针法（验证菜单真开在服务端）：向菜单槽位 `/item replace entity Bot container.<槽>` 一个探针物品 → 菜单开着时它**不在**玩家 Inventory 里、关闭后回到 Inventory。

**不可靠（踩过坑）：**

- ❌ `execute ... run say X`：say 输出走聊天广播，**RCON 响应为空串**，无论条件真假——所有这类断言恒假。
- ❌ 任何依赖 mineflayer `bot.currentWindow`/客户端物品栏状态的断言：1.21.x PartialReadError 解析 desync，时好时坏。
- ⚠️ 未加载区块上的 `execute if block`：新版本（1.21.9+ 出生区块默认不加载）会返回 "That position is not loaded" 或 "Test failed"——远距离探测前先 `/forceload add <x> <z>`（用完 remove）。

**探针自检**：写任何方块断言前，先对已知方块验证探针本身（超平坦 0 -64 0 必是 bedrock）：

```js
'/execute if block 0 -64 0 minecraft:bedrock'  // 必须返回 "Test passed"
'/execute if block 0 -64 0 minecraft:stone'    // 必须返回 "Test failed"
```

## 时序要点

- 服务端逻辑判定类断言要留 tick 缓冲：900ms ≈ 18 ticks；"行为依赖的方块存在性"通常比状态计数器更硬（原版常带每 tick 校验，条件丢了会立刻回退）。
- 唤醒/清理类断言留 ≥1.2s：状态变化 → 下一服务端 tick 才生效清理。
- 测试睡觉类/夜晚类行为：`/difficulty peaceful` + `/kill @e[type=!minecraft:player]` 清场，`/time set midnight` 保证条件；单人服务器全员入睡会自动跳过夜晚——每轮测试开头重设时间。
- 坐标可预测性：`level-type=flat` + 固定出生点，机器人位置可解析。

## 覆盖清单模板（任何交互类 mod 通用）

1. **触发条件正反**：满足条件时生效 / 不满足时拒绝（读服务端状态计数器或结果消息）
2. **持久性**：生效后留置一段时间不复位（抓"每 tick 校验"类回退）
3. **资源不泄漏**：持有的物品不被消耗；临时放置的方块/实体被清理干净（方块断言为空）
4. **界面类**：打开 → 服务端探针证明菜单真实持有物品 → 关闭后归还
5. **写入持久**：写入 → 关闭 → `/data get entity Bot HandItems[N]` 组件里包含该数据
6. **读入安全**：预装数据的物品开关一轮后数据不丢
7. **优先级**：与原版行为冲突的场景（瞄准有界面的方块）原版胜出
8. **枚举遍历**：方向/颜色/变体类行为，对每个枚举值逐一断言方块状态，不接受"默认值碰巧对"
9. **平台矩阵**：同一套断言在每个（版本 × 加载器）构建上都跑一遍——"某平台独有 bug"大概率是漏改一处，先 diff 四个项目
