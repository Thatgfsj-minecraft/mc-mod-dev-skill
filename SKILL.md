---
name: minecraft-mod-dev
description: Minecraft Java 模组开发技能：双版本（1.21.1 / 1.21.11）× 双加载器（Fabric / NeoForge）的环境搭建、构建、E2E 测试与发布全流程。当需要新建/修改/测试/发布 Minecraft 模组，或排查 Loom/Gradle/映射/跨版本 API 差异/专用服务器测试问题时使用。Use when developing, building, testing or releasing Minecraft mods across multiple MC versions and mod loaders.
---

# Minecraft 模组开发（双版本 × 双加载器）

开发任何 Minecraft Java 模组都适用的标准流程与经验：环境搭建、多版本多加载器项目结构、构建测试发布，以及一套经过实战验证的排查方法论。

## 0. 核心原则（先读这个）

1. **不要凭记忆写 Minecraft 代码**。Mojmap API 在小版本间就会改名/改签名（见 `references/version-diffs.md`），动手前先确认当前版本的写法：用 loom-cache 里的 `*-sources.jar` 反编译原版源码，或先写 1.21.1 再编译 1.21.11 让差异暴露出来。
2. **交互逻辑一律服务端权威**：客户端事件处理器永远返回 `PASS`，让原版包照常流动；客户端伪造 `SUCCESS` 会吞掉后续 use 包，产生"界面闪一下就关"这类难查的 bug。
3. **行为验证只信专用服务器 + RCON 服务端断言**，mineflayer 等客户端机器人状态在 1.21.x 有解析缺陷（见 `references/e2e-testing.md`）。
4. 查复杂 bug 时**让子代理去读源码调查、你看它的调查**，不要在脑内推演——幻觉率高；结论必须落到"文件:行号"级别的证据。

## 1. 开发环境

| 项 | 值 | 说明 |
|---|---|---|
| JDK | 21（源码级 release=21） | Temurin/Corretto 21 均可；`fabric.mod.json` 声明 `java >= 21` |
| Gradle | wrapper 9.5.1 | 每个子项目自带 wrapper |
| Fabric Loom | **1.21.x 目标钉死 1.17.21** | Loom 1.18+ 要求 JVM 25 跑 Gradle；做 1.21.x 别升 |
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
| 依赖范围声明 | `>=1.21.1 <1.21.2` | `>=1.21.11 <1.21.12` |

## 2. 项目布局：四子项目复制式 core（推荐结构）

```
repo/
  1.21.1/fabric/     1.21.1/neoforge/
  1.21.11/fabric/    1.21.11/neoforge/     ← 每个都是完整独立 Gradle 项目
      src/main/java/<pkg>/            ← core 类四个项目保持一致（刻意不抽公共库）
      src/main/java/<pkg>/fabric/     ← 仅 Fabric 入口类
      src/main/java/<pkg>/neoforge/   ← 仅 NeoForge 入口类
      src/main/resources/
          fabric.mod.json | META-INF/neoforge.mods.toml
          assets/<id>/lang/{en_us,zh_cn}.json
          data/<id>/tags/item/*.json
```

- **为什么不抽公共库**：四份 core 保持可独立编译、独立审查，`diff` 两个版本目录就直接得到版本差异清单；公共库会引入多项目构建与 remap 顺序问题，收益配不上复杂度。
- 改 core 逻辑 = 同一改动落四个项目。
- 版本号在各子项目 `build.gradle` 的 `version = 'x.y.z'`，`processResources` 会展开进 `fabric.mod.json` / `neoforge.mods.toml`——**发版要改四处**。
- 需要 mod 兼容时，tags 用**可选引用**：`{ "id": "#c:<类别>", "required": false }`——任何模组物品打上 `#c:` 标签即自动获得支持，目标环境没有该标签也不报错。建议内置覆盖全部原版变体（颜色、损坏阶段等），别只写一个默认变体。

## 3. 双版本差异（1.21.1 ↔ 1.21.11）

跨这两个版本时，API 差异是**点状、可枚举**的，逐条对照表见 `references/version-diffs.md`。最常遇到的：

| 1.21.1 | 1.21.11 | 场景 |
|---|---|---|
| `InteractionResult.SUCCESS` | `InteractionResult.SUCCESS_SERVER` | 纯服务端动作（开菜单、睡觉等）。用错会让客户端表现异常 |
| `ResourceLocation.fromNamespaceAndPath` | `Identifier.fromNamespaceAndPath` | 资源定位 |
| `container.startOpen(Player)` | `container.startOpen(ContainerUser)` | 容器开关；取实体用 `user.getLivingEntity()` |
| `problem.getMessage()` | `problem.message()` | `BedSleepingProblem` 文案 |

另外：1.21.11 删除了 `DimensionType.bedWorks()`，改用环境属性 `EnvironmentAttributes.BED_RULE` 的 `BedRule`；`player.serverLevel()` 收回为协变的 `player.level()`。

## 4. 双加载器差异（core 共享，入口独立）

推荐把加载器差异全部隔离到唯一入口类，core 类不 import 任何加载器类：

| 关注点 | Fabric | NeoForge |
|---|---|---|
| 入口 | `implements ModInitializer` | `@Mod(MOD_ID)` 构造函数 |
| 右键方块 | `UseBlockCallback.EVENT.register`，不处理返回 `PASS` | `@SubscribeEvent PlayerInteractEvent.RightClickBlock`，处理后 `setCanceled(true)` + `setCancellationResult(result)` |
| 右键物品 | `UseItemCallback.EVENT`，返回 `InteractionResultHolder`（1.21.1）/ `InteractionResult`（1.21.11，签名变了） | `PlayerInteractEvent.RightClickItem` |
| 服务端 tick | `ServerTickEvents.END_SERVER_TICK` | `NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post)` |
| 配置目录 | `FabricLoader.getInstance().getConfigDir()` | `FMLPaths.CONFIGDIR.get()` |

注意：**Fabric API 自己也在小版本间改回调签名**（UseItemCallback 返回类型 1.21.1→1.21.11 变了），NeoForge 事件 API 跨这两个版本更稳。完整对照见 `references/loader-diffs.md`。

## 5. 通用工程原则（防两类高发 bug）

1. **没有方块锚点的菜单必须覆写 `stillValid`**：任何"从物品/远程打开界面"的 mod（便携合成、随身容器等），原版 `stillValid(ContainerLevelAccess)` 会检查锚点方块类型，不符合就立即关界面——子类覆写 `stillValid(p) { return p.isAlive(); }`。
2. **写回型数据结构要防御**：以物品组件为存储的容器，`stillValid` 用**引用相等**而不是 `contains`（组件相等会让两个内容相同的物品互相顶替）；打开前做**容量上限检查**（菜单槽位有硬上限，超限写回会静默丢数据）；拒绝会把存储物品放进去的嵌套写入；每个会"临时放置方块/实体"的功能都要有 tick 级清理器，且清理时抑制掉落（`Block.UPDATE_SUPPRESS_DROPS`）防复制。
3. **模仿原版行为的功能，先反编译原版确认它的前置校验和每 tick 校验**（例如睡觉类功能：`startSleepInBed` 无条件读方块状态、`LivingEntity.tick` 每 tick 检查床存在），再决定怎么满足条件，否则会出现"对空气调用即崩""瞬间回退"。
4. **配置解析不要依赖 Gson 自动反序列化**：逐字段手写解析 + 显式默认值 + 越界归一化，保证缺键、旧文件、手改坏值都不会破坏行为。
5. **由实体朝向决定的行为，实际建模在方块状态上**：涉及朝向/位置的行为（放置、生成、定向），确认数据真正存在哪个属性里（`FACING` 在方块上而不是实体上），验收时对每个枚举值逐一断言。

## 6. 构建与测试流程

```bash
# 构建（每子项目独立）
cd <mc>/<loader> && GRADLE_USER_HOME=/path/gradle-home ./gradlew build
# 产物：build/libs/<archivesName>-<version>.jar

# 测试体系（一次性搭好，长期复用；详见 references/e2e-testing.md）
#   专用 Fabric 服务器：mods/ 放 fabric-api + 本模组 jar
#   server.properties：online-mode=false、enable-rcon=true、rcon.port=25575、level-type=flat
#   eula.txt：eula=true
cd server-dir && java -Xmx2G -jar fabric-server.jar nogui     # 等 "Done"
node e2e.js                                                   # mineflayer 机器人 + RCON 断言
```

建议给模组加一个**启动自检**（服务端启动时跑一次核心数据读写回环，日志输出 `SELF-TEST PASS`）——任何版本升级、映射变化都能第一时间暴露数据层问题。测试写法与断言模式见 `references/e2e-testing.md`，**尤其注意 RCON 探针的两个坑**：mineflayer 客户端状态不可信；`execute ... run say` 的输出不回传 RCON，要用裸 `execute if block`（返回 `Test passed`/`Test failed`）。

## 7. 发布流程

1. 四个子项目 `build.gradle` 版本号统一升级；
2. 构建四个 jar，测试服部署 + E2E 全绿 + 启动自检 PASS（有新版本时旧版测试服也要换新 jar 重跑）；
3. 提交（风格 `vx.y.z: 摘要`），打 tag `vx.y.z`，`git push origin main --tags`；
4. `gh release create vx.y.z --title ... --notes ... <各平台jar路径>`（release notes 列变更 + 验证方式）；
5. CI 放在 `ci/build.yml`（推 `.github/workflows/` 需要 token 有 workflow scope：`gh auth refresh -h github.com -s workflow`）；matrix 里 **artifact 名不能带 `/`**，用无斜杠别名数组。
