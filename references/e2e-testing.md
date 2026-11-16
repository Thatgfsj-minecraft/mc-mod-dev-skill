# E2E 测试：专用服务器 + 服务端权威断言（环境自适应）

适用于任何有玩家交互行为的模组，**任何版本 / Loader**：机器人负责"像玩家一样操作"，服务端 RCON 负责"权威判定结果"。技术栈：node + mineflayer + minecraft-data + 零依赖手写 RCON 客户端。

命令面（RCON、`/execute`、`/data`、`/item`、`/time`…）是原版能力，与 Loader 无关，断言模式跨 Loader 复用；**服务器安装与启动方式随 Loader 而定**（见下）。

**版本下界**：本文命令语法按 **1.13+** 书写（`/item` 系为 **1.17+**；1.13–1.16 用 `/replaceitem`，1.12 及更早是老式 `/execute <实体> <x> <y> <z>` 语法）。更老版本的命令面不同，动手前按目标版本核实命令语法。

## 架构分工

| 角色 | 职责 | 明确不做 |
|---|---|---|
| mineflayer 机器人 | join、lookAt（设朝向）、activateItem（右键空气 / 物品）、activateBlock（右键方块） | **不做任何状态断言**（客户端解析在部分版本有 PartialReadError 缺陷，见 pitfalls D1） |
| RCON | `/item replace` 塞物品、`/data get entity` 读 NBT、`/execute if block` 方块断言、`/time set`、`/difficulty` | — |
| 专用服务器 | 唯一真相来源；服务器日志零异常 = 服侧无 bug 的必要条件 | — |

## 服务器搭建（按目标 Loader 选安装路径）

先确认目标环境（`SKILL.md` §3），按 Loader 选安装方式：

| Loader | 安装 | 启动 |
|---|---|---|
| Fabric / Quilt | 官方 meta 拉 server launcher jar（首跑自动装好 loader） | `java -Xmx2G -jar <launcher>.jar nogui` |
| NeoForge | `java -jar neoforge-<v>-installer.jar --installServer` | 运行安装生成的 `run.sh`（`@libraries/...` 路径参数） |
| Forge | `java -jar forge-<v>-installer.jar --installServer` | 同上 |

共同步骤（各 Loader 一致）：

1. `mods/` 放目标 Loader 的依赖（Fabric 项目放 `fabric-api.jar`）+ 本模组 jar——**必须用目标构建的产物**，多构建项目每个目标各一套测试服。
2. `server.properties` 关键项：

   ```properties
   online-mode=false
   enable-rcon=true
   rcon.password=testpass
   rcon.port=25575
   level-type=minecraft\:flat
   spawn-protection=0
   ```

3. `eula.txt`：`eula=true`；给 Bot 发 op（level 4）可让 chat 命令兜底。
4. 多套并行：RCON 端口错开（25575/25576...）。

## 客户端 run 目录预置（自动化友好）

专用服务器的断言是回归基线，但**客户端侧**（`gradle runClient` / IDE 开发实例）同样要被脚本/AI 驱动：默认配置下它会停在开场引导屏、多人游戏警告屏，或者窗口一失焦就弹 PauseScreen——自动化还没开始就被自己挡住。启动前预置 `run/options.txt`：

| 键 | 值 | 作用 | 依据 |
|---|---|---|---|
| `pauseOnLostFocus` | `false` | 窗口失焦不自动暂停（否则截图/命令执行期间弹出 PauseScreen） | `Options.pauseOnLostFocus`，默认 true |
| `skipMultiplayerWarning` | `true` | 跳过多人游戏警告确认屏 | `Options.skipMultiplayerWarning` |
| `onboardAccessibility` | `false` | 跳过首次启动的辅助功能引导屏（默认 true，新实例第一步就卡在这） | `Options.onboardAccessibility` |
| `tutorialStep` | `none` | 跳过新手教程提示 | `TutorialSteps.NONE`（`Options.tutorialStep`） |

> [实测：1.21.11 反编译] 四个键都由 `Options` 的 `processOptions` 按 `options.txt` 的 `key:value` 行读入（`Splitter.on(':')`）；`tutorialStep` 存的是枚举名 `none`。key 名跨版本基本稳定，但**改目标版本后仍应按 `environment-discovery.md` 的流程核实一次**（本文以 1.21.11 为验证版本）。

**写入时机**：首次 `gradle runClient` 会创建 `run/` 并在退出时写出默认 `options.txt`；预置可以在首次运行前手工建 `run/options.txt`，也可以在首次运行之后、**下一次启动之前**写入——只要游戏启动时文件已在，就会被读入。CI 每次清 `run/`（clean 工作区）都必须重写，所以这段逻辑要放进**启动脚本**，而不是只做一次的手工操作。

bash 写法（只增改这四个键，保留其余设置）：

```bash
set_opt() {  # 用法: set_opt key value
  mkdir -p run && touch run/options.txt
  awk -F: -v k="$1" -v v="$2" '
    $1 == k { print k ":" v; found = 1; next }
    { print }
    END { if (!found) print k ":" v }
  ' run/options.txt > run/options.txt.tmp && mv run/options.txt.tmp run/options.txt
}
set_opt pauseOnLostFocus false
set_opt skipMultiplayerWarning true
set_opt onboardAccessibility false
set_opt tutorialStep none
```

node 写法（可直接放进 e2e 启动脚本）：

```js
const fs = require("node:fs");
const path = require("node:path");

function presetClientOptions(dir, want) {
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, "options.txt");
  const kept = fs.existsSync(file)
    ? fs.readFileSync(file, "utf8").split(/\r?\n/).filter((line) => line.trim() !== "")
    : [];
  const keys = new Set(Object.keys(want));
  const merged = kept.filter((line) => {
    const i = line.indexOf(":");
    return i < 0 || !keys.has(line.slice(0, i)); // 丢掉要改写的旧行
  });
  for (const [k, v] of Object.entries(want)) merged.push(`${k}:${v}`);
  fs.writeFileSync(file, merged.join("\n") + "\n");
}

presetClientOptions("run", {
  pauseOnLostFocus: "false",
  skipMultiplayerWarning: "true",
  onboardAccessibility: "false",
  tutorialStep: "none",
});
```

- **不要整文件覆盖**：`options.txt` 还有几十上百个键（键位、音量、语言…），覆盖会冲掉用户配置；只增改需要的键。
- Loom 默认 `runDir` 是项目根的 `run/`；如果项目在 `build.gradle` 的 `runs { client { runDir ... } }` 里改过目录，脚本路径要跟着改。
- **F3+P 是原版切换键**（1.21.11 `KeyboardHandler.handleDebugKeys` 里 `options.keyDebugFocusPause` 切换 `pauseOnLostFocus` 并 `options.save()`，[实测：1.21.11 反编译]），但**自动化不要靠按键注入**——窗口焦点、键码、输入法都不可靠。用 `options.txt` 预置 + 运行时 `mc_screen_current` 读 `pause_on_lost_focus` 核实；需要临时恢复原版行为做兼容测试时用 `mc_pause_on_lost_focus({enabled:true})`。
- **桥接 Mod 0.2.0+ 启动会强制关一次**（`pauseOnLostFocus=false` + 失焦自动暂停守卫，见 `environments/versions/1.21.11/bridge.md`），这是最后一道保险；但只覆盖装了该 Mod 的客户端，纯原版客户端仍要靠 options.txt。
- **options.txt 是"建议预置"而非硬保证**：原版在退出/改设置时会**重写整个文件**（[实测：1.21.11 反编译] `Options.save` 遍历全部选项写盘）。若有人在游戏里按了 F3+P、或兼容性测试把 `pause_on_lost_focus` 打开过，下次写盘会保留该值——所以每次启动前都要重写，而不是只在镜像里预置一次。

## 运行序列

> 客户端侧自动化（`runClient`/IDE 开发实例）启动前，先按上一节「客户端 run 目录预置」写入 `run/options.txt`；桥接 Mod 0.2.0+ 启动时还会再强制关一次失焦暂停（双保险）。服务器基线不受影响。

```bash
cd server-dir && <上表对应启动命令>                            # 必须在 server 目录里跑
# 等日志 "Done" 与模组启动自检输出（建议模组带 SELF-TEST）
node e2e.js                                                    # 结束时自动 /stop，退出码=失败数
```

RCON 大响应注意：首包 ≥4000 字节时要发空命令取结束标记排空。

`pause-when-empty-seconds` 触发的空载暂停期间，RCON 与 forceload 仍可执行、断言不受暂停影响（[实测：1.21.8 移植批次]）。

重启 / 关服注意（详见 pitfalls D4/D5/D6）：`/stop` 后等**进程退出**再重启（端口先关、world 保存可拖 2-3 分钟，`session.lock` 未释放就起新实例会崩）；watchdog 强杀过的世界先删档再用；bot 远距传送前先 forceload 目标区块（**虚空维度例外**：forceload 自身会死锁，见 pitfalls D7 与下方 ⚠️）。

## 断言模式（经过实战校正）

**可靠：**

- `/data get entity Bot <路径>` → 内联返回 `entity data: <值><类型后缀>`（`s`=short、`f`=float、`d`=double）。**注意**：查具体路径时不回显字段名，匹配数值别匹配字段名。
- `/data get entity Bot Pos` → 解析 `[x d, y d, z d]` 后取方块坐标。**注意负数**：`Math.floor(-4.5) = -5`，实体坐标转方块坐标必须用 floor。
- 裸方块断言：`/execute if block X Y Z <方块id>[<属性>=<值>,...]` → 返回内联 `Test passed` / `Test failed`。tag 形式 `#minecraft:<tag>` 同样支持。
- 物品探针法（验证菜单真开在服务端）：向菜单槽位 `/item replace entity Bot container.<槽>` 一个探针物品 → 菜单开着时它**不在**玩家 Inventory 里、关闭后回到 Inventory。

**不可靠（踩过坑）：**

- ❌ `execute ... run say X`：say 输出走聊天广播，**RCON 响应为空串**，无论条件真假——所有这类断言恒假。
- ❌ `execute in <dim> run /execute if block ...`（嵌套时 run 后多带斜杠）：返回 `Incorrect argument` 而非报错，表现为静默假 FAIL——拼接嵌套命令剥掉内部斜杠，每条响应原样落盘（详见 pitfalls D3）。
- ❌ 任何依赖 mineflayer `bot.currentWindow` / 客户端物品栏状态的断言：客户端解析 desync，时好时坏（1.21.x 实测）。
- ⚠️ 未加载区块上的 `execute if block`：新版本（实测 1.21.9+ 出生区块默认不加载）会返回 "That position is not loaded" 或 "Test failed"——远距离探测前先 `/forceload add <x> <z>`（用完 remove）。**例外（pitfalls D7）**：虚空 `noise_settings` 的维度里 forceload 远块自己就会死锁 tick，命令驱动的操作只落在出生附近已生成区块。
- ⚠️ `forceload` 状态跨重启持久（是 world saved data，重启日志可见 "Loading N persistent chunks"）：重启后 `forceload add` 可能返回 "No chunks were marked"——别按"本会话没 forceload 过"假设区块状态。
- ⚠️ Windows + Git Bash 下 node 脚本 `require('/o/...')` 报 MODULE_NOT_FOUND：node 不认 Git Bash 路径，写盘符形式 `require('O:/...')`。

**探针自检**：写任何方块断言前，先对已知方块验证探针本身（超平坦 0 -64 0 必是 bedrock）：

```js
'/execute if block 0 -64 0 minecraft:bedrock'  // 必须返回 "Test passed"
'/execute if block 0 -64 0 minecraft:stone'    // 必须返回 "Test failed"
```

## 时序要点

- 服务端逻辑判定类断言要留 tick 缓冲：900ms ≈ 18 ticks；"行为依赖的方块存在性"通常比状态计数器更硬（原版常带每 tick 校验，条件丢了会立刻回退）。
- 唤醒 / 清理类断言留 ≥1.2s：状态变化 → 下一服务端 tick 才生效清理。
- 测试睡觉类 / 夜晚类行为：`/difficulty peaceful` + `/kill @e[type=!minecraft:player]` 清场，`/time set midnight` 保证条件；单人服务器全员入睡会自动跳过夜晚——每轮测试开头重设时间。
- 坐标可预测性：`level-type=flat` + 固定出生点，机器人位置可解析。

## 覆盖清单模板（任何交互类 mod 通用）

1. **触发条件正反**：满足条件时生效 / 不满足时拒绝（读服务端状态计数器或结果消息）
2. **持久性**：生效后留置一段时间不复位（抓"每 tick 校验"类回退）
3. **资源不泄漏**：持有的物品不被消耗；临时放置的方块 / 实体被清理干净（方块断言为空）
4. **界面类**：打开 → 服务端探针证明菜单真实持有物品 → 关闭后归还
5. **写入持久**：写入 → 关闭 → `/data get entity Bot HandItems[N]` 组件里包含该数据
6. **读入安全**：预装数据的物品开关一轮后数据不丢
7. **优先级**：与原版行为冲突的场景（瞄准有界面的方块）原版胜出
8. **枚举遍历**：方向 / 颜色 / 变体类行为，对每个枚举值逐一断言方块状态，不接受"默认值碰巧对"
9. **目标矩阵**：同一套断言在项目的每个目标构建（每个 版本 × Loader）上都跑一遍——"某平台独有 bug"大概率是漏改一处，先 diff 各构建同源文件
