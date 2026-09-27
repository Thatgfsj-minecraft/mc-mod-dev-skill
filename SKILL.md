---
name: minecraft-mod-dev
description: Minecraft Java 模组开发技能：双版本（1.21.1 / 1.21.11）× 双加载器（Fabric / NeoForge）的环境搭建、构建、E2E 测试与发布全流程。当需要新建/修改/测试/发布 Minecraft 模组，或排查 Loom/Gradle/映射/跨版本 API 差异/专用服务器测试问题时使用。Use when developing, building, testing or releasing Minecraft mods across multiple MC versions and mod loaders.
---

# Minecraft 模组开发（双版本 × 双加载器）

本技能来自 [handy-shulkers](https://github.com/Thatgfsj-minecraft/handy-shulkers) 项目的完整实战沉淀：一个同时支持 MC 1.21.1 / 1.21.11、Fabric / NeoForge 四个构建的模组，含专用服务器 E2E 测试体系。所有结论都有代码与日志证据，不是推测。

## 0. 核心原则（先读这个）

1. **不要凭记忆写 Minecraft 代码**。Mojmap API 在小版本间就会改名/改签名（见 `references/version-diffs.md`），动手前先 diff 参考项目，或用 loom-cache 里的 `-sources.jar` 反编译原版源码确认。
2. **交互逻辑一律服务端权威**：客户端事件处理器永远返回 `PASS`，让原版包照常流动；客户端伪造 `SUCCESS` 会吞掉后续 use 包，导致"界面闪一下就关"这类 bug。
3. **判断行为的唯一可信来源是专用服务器 + RCON 服务端断言**，mineflayer 客户端状态在 1.21.x 有解析缺陷（见 `references/e2e-testing.md`）。
4. 查 bug 时**让子代理去读源码调查、你看它的调查**，不要在脑内推演——幻觉率高。

## 1. 开发环境

| 项 | 值 | 说明 |
|---|---|---|
| JDK | 21（源码级 release=21） | Temurin/Corretto 21 均可；`fabric.mod.json` 声明 `java >= 21` |
| Gradle | wrapper 9.5.1 | 四个子项目各自带 wrapper |
| Fabric Loom | **钉死 1.17.21** | Loom 1.18+ 要求 JVM 25 跑 Gradle；做 1.21.x 别升 |
| ModDevGradle | 2.0.147 | NeoForge 侧插件（不是 NeoGradle/Architectury） |
| 映射 | Mojmap（`loom.officialMojangMappings()`） | 官方映射，跨版本差异参考 references |

**隔离 GRADLE_USER_HOME（关键）**：如果机器上有全局 Gradle init 脚本（如阿里云镜像 `~/.gradle/init.gradle`），NeoForge 插件/依赖会在镜像上解析失败。做法：

```bash
# 用一个干净的 GRADLE_USER_HOME（里面没有 init.gradle）
GRADLE_USER_HOME=/path/to/gradle-home ./gradlew build
# 若 services.gradle.org 下载 wrapper 超时，从全局 ~/.gradle/wrapper/dists 预拷 gradle-9.5.1-bin
```

**版本坐标速查**（1.21.x 双版本）：

| | 1.21.1 | 1.21.11 |
|---|---|---|
| fabric-api | 0.116.17+1.21.1 | 0.141.6+1.21.11 |
| NeoForge | 21.1.252 | 21.11.45 |
| fabric-loader（构建期） | 0.19.3 | 0.19.3 |
| fabric.mod.json 依赖 | `>=1.21.1 <1.21.2` | `>=1.21.11 <1.21.12` |

## 2. 项目布局：四子项目复制式 core

```
repo/
  1.21.1/fabric/     1.21.1/neoforge/
  1.21.11/fabric/    1.21.11/neoforge/     ← 每个都是完整独立 Gradle 项目
      src/main/java/<pkg>/            ← core 类四个项目逐字节相同（刻意不抽公共库）
      src/main/java/<pkg>/fabric/     ← 仅 Fabric 入口类
      src/main/java/<pkg>/neoforge/   ← 仅 NeoForge 入口类
      src/main/resources/
          fabric.mod.json | META-INF/neoforge.mods.toml
          assets/<id>/lang/{en_us,zh_cn}.json
          data/<id>/tags/item/*.json
```

- **为什么不抽公共库**：四份 core 保持可独立编译、独立审查、diff 即版本差异清单；公共库要引入多项目构建与 remap 顺序问题，收益配不上复杂度。
- 改 core 逻辑 = 同一改动落四个项目（可以用同一份 Edit 内容重复应用）。
- 版本号在各子项目 `build.gradle` 的 `version = 'x.y.z'`，`processResources` 会展开进 `fabric.mod.json` / `neoforge.mods.toml`——**发版要改四处**。
- tags 用**可选引用**做 mod 兼容：`{ "id": "#c:shulker_boxes", "required": false }`，任何模组物品打上 `#c:` 标签即自动获得支持，不存在的标签不报错。

## 3. 双版本差异（1.21.1 ↔ 1.21.11）

11/15 个 core 文件两版本逐字节相同；差异集中在交互与容器侧。完整对照表见 `references/version-diffs.md`，最常见的四个：

| 1.21.1 | 1.21.11 | 场景 |
|---|---|---|
| `InteractionResult.SUCCESS` | `InteractionResult.SUCCESS_SERVER` | 纯服务端动作（开菜单、睡觉）。用错会让客户端表现异常 |
| `ResourceLocation.fromNamespaceAndPath` | `Identifier.fromNamespaceAndPath` | 资源定位 |
| `container.startOpen(Player)` | `container.startOpen(ContainerUser)` | 容器开关；取实体用 `user.getLivingEntity()` |
| `problem.getMessage()` | `problem.message()` | `BedSleepingProblem` 文案 |

另外：1.21.11 删除了 `DimensionType.bedWorks()`，改用 `level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos)` 的 `BedRule`；`ServerLevel` 侧 `player.serverLevel()` 收回为协变的 `player.level()`。

## 4. 双加载器差异（core 共享，入口独立）

15 个 core 文件在 Fabric 与 NeoForge 之间 **0 行差异**；加载器差异全部隔离在唯一入口类：

| 关注点 | Fabric | NeoForge |
|---|---|---|
| 入口 | `implements ModInitializer` | `@Mod(MOD_ID)` 构造函数 |
| 右键方块 | `UseBlockCallback.EVENT.register`，不处理返回 `PASS` | `@SubscribeEvent PlayerInteractEvent.RightClickBlock`，处理后 `setCanceled(true)` + `setCancellationResult(result)` |
| 右键物品 | `UseItemCallback.EVENT`，返回 `InteractionResultHolder`（1.21.1）/ `InteractionResult`（1.21.11，签名变了） | `PlayerInteractEvent.RightClickItem` |
| 服务端 tick | `ServerTickEvents.END_SERVER_TICK` | `NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post)` |
| 配置目录 | `FabricLoader.getInstance().getConfigDir()` | `FMLPaths.CONFIGDIR.get()` |

注意：**Fabric API 自己也在小版本间改回调签名**（UseItemCallback 返回类型 1.21.1→1.21.11 变了），NeoForge 事件 API 反而更稳。

## 5. 反复踩过的功能级坑（速查）

完整"症状 → 根因 → 修复"手册见 `references/pitfalls.md`。最高频的五个：

1. **便携菜单闪一下就关**：原版 `stillValid(access)` 检查锚点方块类型 → 子类菜单覆写 `stillValid(p) { return p.isAlive(); }`。
2. **手持床睡觉瞬间醒来**：`LivingEntity.tick` 每 tick `checkBedExists()`，床上没有真床方块就强制醒来 → 必须先放真床（FOOT+HEAD 两个方块）再 `startSleepInBed`，用 tracker 在醒来/下线时清理（删床加 `Block.UPDATE_SUPPRESS_DROPS` 防复制）。
3. **对着空气调用 `startSleepInBed` 直接崩**：`ServerPlayer.startSleepInBed` 第一行就读 `getBlockState(pos).getValue(FACING)` → 床必须先落地。
4. **配置项 true 静默变 false**：`Gson.fromJson` 不跑构造器，缺键会用字段初始化值以外的方式重置 → 逐字段手写 JSON 解析。
5. **床朝向固定**：放床只设 `PART` 不设 `FACING` → 睡姿永远一个方向；`bed.setValue(BedBlock.FACING, player.getDirection())`，并用 `hasProperty` 兜底 modded 伪床。

## 6. 构建与测试流程

```bash
# 构建（每子项目独立）
cd <mc>/<loader> && GRADLE_USER_HOME=/path/gradle-home ./gradlew build
# 产物：build/libs/<archivesName>-<version>.jar

# 测试体系（一次性搭好，长期复用）
#   专用 Fabric 服务器：fabric-server.net 拉 fabric-server.jar，mods/ 放 fabric-api + 本模组 jar
#   server.properties：online-mode=false、enable-rcon=true、rcon.port=25575、level-type=flat
#   eula.txt：eula=true
cd server-dir && java -Xmx2G -jar fabric-server.jar nogui     # 前台或后台，等 "Done"
node e2e.js                                                   # mineflayer 机器人 + RCON 断言
```

启动日志必须出现 `[handyshulkers] SELF-TEST PASS`（模组自带容器读写回环自检）。测试写法与断言模式见 `references/e2e-testing.md`——**尤其注意 RCON 探针的两个坑**（mineflayer 客户端状态不可信；`execute ... run say` 的输出不回传 RCON，要用裸 `execute if block`，返回 `Test passed`/`Test failed`）。

## 7. 发布流程

1. 四个子项目 `build.gradle` 版本号统一升级；
2. 构建四个 jar，测试服部署 + E2E 全绿 + 启动自检 PASS；
3. 提交（风格 `vx.y.z: 中文/英文摘要`），打 tag `vx.y.z`，`git push origin main --tags`；
4. `gh release create vx.y.z --title ... --notes ... <四个jar路径>`（release body 用中文写变更+验证方式）；
5. CI 放在 `ci/build.yml`（推 `.github/workflows/` 需要 token 有 workflow scope：`gh auth refresh -h github.com -s workflow`）；matrix 里 **artifact 名不能带 `/`**，用无斜杠别名。
