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
- **桥接 Mod 0.4.0+ 仅在开发者模式激活后**才强制关一次 `pauseOnLostFocus`（运行期每帧校正，见 `environments/versions/1.21.11/bridge.md`）；`-Dmcb.devMode=false` 或弹窗点【否】时保持原版选项，纯原版客户端/关闭态仍要靠 options.txt。
- **options.txt 是"建议预置"而非硬保证**：原版在退出/改设置时会**重写整个文件**（[实测：1.21.11 反编译] `Options.save` 遍历全部选项写盘）。若有人在游戏里按了 F3+P、或兼容性测试把 `pause_on_lost_focus` 打开过，下次写盘会保留该值——所以每次启动前都要重写，而不是只在镜像里预置一次。

### 开发者模式弹窗旁路（bridge-mod 0.4.0+）

bridge-mod 0.4.0 起**默认启动不监听调试端口**：进入主页面会弹一次“是否开启开发者模式？”确认屏。无人值守的 `gradle runClient` / IDE 开发实例如果只是等 MCP 连接，会一直停在确认屏上——这是比 PauseScreen 更隐蔽的卡死，因为游戏本身没有报错。

**自动化启动必须显式带 JVM 参数**：

```bash
# gradle：通过 JAVA_TOOL_OPTIONS 或 init 脚本/JavaExec jvmArgs 注入
JAVA_TOOL_OPTIONS="-Dmcb.devMode=true -Dmcb.port=25585" gradle runClient

# IDE run configuration：VM options
-Dmcb.devMode=true -Dmcb.port=25585
```

- `-Dmcb.devMode=true`：跳过弹窗立即启动桥接（端口监听、MCP 可连、失焦保活生效）；
- `-Dmcb.devMode=false`：跳过弹窗并完全关闭桥接（用于“纯原版行为”对照）；
- 不带该参数且没有 `config/mcb-bridge.properties` 的 `remember_choice=true` 时会弹窗——**CI 里视为失败条件**，启动脚本必须显式传参；
- 端口用 `-Dmcb.port=NNNN` / `MCB_PORT` 指定；并行 runClient 实例要错开端口；
- 选项 1（JVM）优先于配置文件；配置 `remember_choice=true` / `dev_mode=...` 只是第二层；
- 若确认屏曾把 `pauseOnLostFocus` 关掉，那是点【是】后的预期行为；点【否】/`-Dmcb.devMode=false` 时 bridge 不应改任何原版选项（版本卡的运行时记录有对照）。

> 本节与上一节的 `options.txt` 预置**互补**：`devMode=true` 时桥接会在启动后关 `pauseOnLostFocus`；`devMode=false` 或未装桥接时，仍要靠 `options.txt` 预置来防失焦暂停。

## 测试世界基线（Fabric 1.21.11，v0.5.4）

> **位置**：本节是"测试前先把世界拉干净"的双端基线——客户端集成服用 MCB `mc_world_create`，专用服 E2E 用 `server.properties` + 启动 bootstrap 命令。协议契约见 `debug-mcp/protocol.md` §8，客户端工具用法见 `debug-mcp.md` §6.9。
>
> **依据**：`server.properties` 键名、超平坦 `generator-settings` 字段、gamerule id 均按 **1.21.11 反编译核实**（`DedicatedServerProperties` / `FlatLevelGeneratorSettings` / `FlatLayerInfo` / `FlatLevelSource` / `GameRules`）；客户端集成服的实际出生点 / 刷怪 / 天气行为已于 2026-11-17 runClient 真机对照通过（四预设建图、gamerule、世界内重建、删除路径；详见版本卡 `bridge.md` 的 v0.5.4 运行时记录）；**专用服侧**的 `server.properties` 键名与生成器字段仍为反编译核实、未起专用服逐项对照。

### 客户端集成服：首次进世界前先建图

AI 自主测试（含 `gradle runClient` / IDE 开发实例）**默认不要进玩家已有的默认生存世界**：野怪、村庄、动物、天气、地形都会污染观察结果。第一次进世界前先按协议 §8 建一个受控测试世界：

```text
mc_status()                                              # 1. 基线：已连上；worldLoaded=false 表示停在主菜单（已在世界内也可直接建，见下）
mc_world_create({ name: "mcb_e2e", preset: "flat_clean" })  # 2. 只传 name + preset，其余默认
mc_status()                                              # 3. 复查：worldLoaded=true、player.gamemode=creative、timeOfDay=6000、raining=false
...                                                      # 4. 搭场景 / 测试 / 截图（见 debug-mcp.md §6）
mc_world_delete({ name: "mcb_e2e", force: true })        # 5. 结束清理
```

**默认值就是"干净世界"**（协议 §8.0；AI 平时不用传，只有下文"两种覆盖默认值的正当理由"才改）：

| 参数 | 默认 | 效果 |
|---|---|---|
| `game_mode` | `creative` | 不会被野怪 / 坠落 / 饥饿打断 |
| `generate_structures` | `false` | 不生成村庄 / 要塞 / 遗迹，地形可预测 |
| `do_mob_spawning` | `false` | 关自然刷怪（**不是**和平模式，见下） |
| `difficulty` | `normal` | 保证 `/summon` 的敌对测试怪不消失 |
| `lock_time` / `lock_weather` | `6000` / `clear` | 锁正午晴天，光照与画面稳定 |
| `keep_inventory` | `true` | 意外死亡不丢测试物品 |
| `seed` | `12345` | 可复现（`vanilla_quiet` 尤其需要） |
| `clean_if_exists` | `true` | 同名存档自动删旧建新 |

**fast-iteration 纪律**：**固定存档名**（建议 `mcb_e2e`）+ `clean_if_exists: true`，每一轮实验都从全新世界开始——残留方块 / 实体 / 掉落物是"上一轮通过、这一轮失败"的头号假故障源。不要在自动化流程里手工新建 / 切换存档；测试结束 `mc_world_delete` 清理（只允许删 `mcb_` 前缀或带 `.mcb-world.json` 标记的测试存档，玩家手工存档受 `WORLD_DELETE_FORBIDDEN` 硬保护）。

**只在这两种情况下覆盖默认值**（其余情况"图省事"改默认会让观察结果失真）：

1. 本次测试的就是**生存机制**（挖掘耗时、饥饿、死亡掉落）→ `game_mode: "survival"`（或测试中途 `mc_command({command:"gamemode survival"})`）；
2. 本次测试的就是**结构生成**（自定义地牢 / 遗迹 / 村庄建筑）→ `generate_structures: true`。

**失败降级**：`BAD_OP` = 桥接 Mod < 0.5.0（协议 v0.5.4 才新增 `world.*` op），回退"手工进一次性测试世界"的旧流程（仍遵守本节预置）；`CLIENT_ONLY` = 连的是专用服桥接（建图 / 删图是客户端专有能力，协议 §8）；`TIMEOUT` = 建图超时（默认看门狗 120s，慢机器显式传 `timeout_ms`；协议 op 字段名是 `timeoutMs`，MCP 工具层是 `timeout_ms`）。

**已在世界里也能直接建**：`world.create` 会先退出当前世界、退回主菜单，再删旧建新（`world.progress` 阶段 `disconnecting` → `deleting` → `creating` → `awaiting_join` → `applying`）。方便，但**当前世界未落盘的状态有丢失风险**——自动化流程仍应在受控测试世界里操作，不要把玩家存档当作临时落点（删除玩家存档另有 `WORLD_DELETE_FORBIDDEN` 红线）。

### 专用服 server.properties：4 套预设的双端对齐

专用服 E2E 断言是回归基线，世界形状要和客户端预设对齐，否则"客户端能过、专用服不过"的差异会浪费整轮排查。**公共键**（4 套预设都写）：

```properties
online-mode=false
enable-rcon=true
rcon.password=testpass
rcon.port=25575
spawn-protection=0
level-name=mcb-e2e
gamemode=creative
difficulty=normal
generate-structures=false
level-seed=12345
```

> `difficulty` 的默认值是 **easy**（1.21.11 `DedicatedServerProperties.difficulty` 默认 `Difficulty.EASY`）——不写就**不是** normal，必须显式写（原因见文末「为什么是 difficulty normal + 关自然刷怪，而不是和平模式」）。

**每套预设的生成配置**（`level-type` 取 world preset id：`minecraft:normal` / `minecraft:flat` / `minecraft:large_biomes` / `minecraft:amplified` / `minecraft:single_biome_surface`，非法值会 warn 并回退 `minecraft:normal`；`generator-settings` **只在 `level-type=minecraft\:flat` 时被解析**，其它预设留空）：

| preset | `level-type` | `generator-settings` | 说明 |
|---|---|---|---|
| `bedrock_lab` | `minecraft\:flat` | `{"layers":[{"block":"minecraft:bedrock","height":3}],"structure_overrides":[],"biome":"minecraft:plains"}` | 超平坦 3×基岩（世界最低 Y 起，1.21.11 为 Y=-64），地基炸不穿、不掉虚空；出生点约 `[0,-61,0]` |
| `flat_clean` | `minecraft\:flat` | `{"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"structure_overrides":[],"biome":"minecraft:plains"}` | 原版超平坦层序（1 基岩 + 2 泥土 + 1 草）；`lakes` / `features` 不写即 false（无湖、无装饰）；出生点约 `[0,-60,0]` |
| `vanilla_quiet` | `minecraft\:normal` | （不写） | 原版主世界 + 固定 seed 12345，关结构；只在需要真实群系 / 矿脉时用 |
| `void_platform` | `minecraft\:flat` | `{"layers":[],"structure_overrides":[],"biome":"minecraft:the_void"}` | 空 `layers` = 全空气虚空；9×9 平台与出生点 server.properties 表达不了，见下节 |

**三个必须显式写的点**（都是 1.21.11 反编译核实的语义，不写行为会漂）：

1. **`structure_overrides: []` 不能省**：超平坦生成器"没写该字段"= **所有结构集都可生成**（`FlatLevelSource.createState` 里 `orElseGet(全部结构集)`），只有空数组才是"结构覆盖为空"。`generate-structures=false` 是另一道闸（`StructureManager` / `ChunkStatusTasks` 检查 `WorldOptions.generateStructures`），两道一起写才是协议 §8.3 要求的对齐。
2. **`layers` 必填**：缺字段会解析失败并报 `No key layers`（`pitfalls.md` D2 已记录同类坑）；`{"layers":[]}` 是合法的纯虚空配置（全员空气即 `voidGen`）。
3. **`biome` 建议显式写**：省略时超平坦 codec 会走 `LOGGER.error("Unknown biome, defaulting to plains")`，在"服务器日志零异常"的判定里是一条假 ERROR——写 `minecraft:plains`（或虚空用 `minecraft:the_void`）即可避免。

> `biome` 不是协议 §8.2 的预设契约（属实现细节）：本表写 `minecraft:plains` 只是为了消掉上面那条假 ERROR；换成 `minecraft:the_void` 会让超平坦场地更"无背景"（适合截图类测试），两者都属等价的服务端配置，断言不受影响。

**属性文件写法**：JSON 直接写在值里即可（`:` 是键值分隔符，值里的冒号不需要转义；`level-type` 按本文件既有惯例写 `minecraft\:flat` 也能正常读）。换行符不要放进值里——一个属性一行。

### server.properties 表达不了的项：启动 bootstrap 命令补齐

下列协议默认值**没有对应的 server.properties 键**（1.21.11 `DedicatedServerProperties` 中不存在），必须在每轮测试开始前用 RCON 命令（或 datapack `minecraft:tick` function）补齐，并逐条检查命令回执：

| 协议默认（§8.0） | 1.21.11 落地方式 | 备注 |
|---|---|---|
| `do_mob_spawning: false` | `/gamerule spawn_mobs false` | gamerule id 是 `spawn_mobs`（不是旧写法 `doMobSpawning`） |
| `lock_time: 6000` | `/gamerule advance_time false` + `/time set 6000` | 先冻结再设时间，否则时间继续走 |
| `lock_weather: "clear"` | `/gamerule advance_weather false` + `/weather clear` | 同上 |
| `fire_tick: false` | `/gamerule fire_spread_radius_around_player 0` | 1.21.11 已无 `doFireTick`；`0` = 只有玩家贴身才会蔓延（等价"基本关闭"），`-1` = 不限制 |
| `keep_inventory: true` | `/gamerule keep_inventory true` | — |
| `allow_commands: true` | **无此键**：专用服建图时 `LevelSettings.allowCommands` 固定传 `false` | 玩家要用命令先 `/op <bot>`；控制台 / RCON 本身是 level 4，不受影响。协议 §8.3 的该映射只对客户端集成服存档有意义 |
| `void_platform` 的平台与出生点 | `/fill -4 64 -4 4 64 4 minecraft:bedrock` + `/setworldspawn 0 65 0` | 平台先铺好再让 bot 进场，否则掉虚空；顺序：进世界 → 铺平台 → `setworldspawn` → 放 bot |

固定 bootstrap 建议（照抄进 e2e 启动脚本，逐条断言回执）：

```text
/gamerule spawn_mobs false
/gamerule advance_time false
/time set 6000
/gamerule advance_weather false
/weather clear
/gamerule fire_spread_radius_around_player 0
/gamerule keep_inventory true
```

### 为什么是 difficulty normal + 关自然刷怪，而不是和平模式

- **和平模式（`peaceful`）下 `/summon` 的敌对怪会立即消失**——战斗、伤害、仇恨、掉落类断言全部做不了，表现为"命令成功了但实体没了"。
- 正确组合是 **`difficulty: normal` + `do_mob_spawning: false`**：没有野怪偷袭，但随时能召唤僵尸 / 骷髅做测试。
- 因此：客户端预设默认 normal；专用服必须显式 `difficulty=normal`（默认 easy 不够）；**不得**为了"安全"把难度调成 peaceful。
- 反例（都会让断言恒假或不可复现）：为省事开 peaceful；开着自然刷怪再靠 `kill @e` 清场（和刷怪赛跑）；把 `spawn_mobs` 当"和平模式替代"却忘了锁时间 / 天气。

> 这套世界基线是**回归测试的前置**，不是回归本身：断言模式（RCON `/execute` `/data` 探针）与覆盖清单不变。客户端侧建图工具的调用纪律见 `debug-mcp.md` §6.9。

## 运行序列

> 客户端侧自动化（`runClient`/IDE 开发实例）启动前，先按「客户端 run 目录预置」写入 `run/options.txt`（桥接 Mod 0.2.1+ 启动时还会再强制关一次失焦暂停，双保险）；**第一次进世界前按「测试世界基线」建测试世界**（`mc_world_create`），不要默认进玩家的生存存档。服务器基线不受影响，但每轮起服后要先跑「测试世界基线」里的 bootstrap gamerule 序列再让 bot 进场。

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
