# Minecraft 1.7.10

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**（`[公开文档：…]` 条目以注明来源为准）；API 差异动手前仍须按 api-verification.md 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 7（运行要求）；构建普遍用 8（现代 JDK 跑不动该年代工具链） |
| 发布线 | 1.7.x 线末代版本（传奇级模组版本，modpack 时代起点） |
| Loader | Forge 10.13.x（事实唯一主流）；LiteLoader 作为轻量补充存在；NeoForge / Fabric / Quilt 均不支持（社区 Legacy Fabric 分叉非主流 `[未核实]`） |
| 映射 | MCP / SRG（`func_…` / `field_…` 名；无 Mojmap / Yarn） |
| 构建系统 | [实测] 原版 `ForgeGradle:1.2-SNAPSHOT` + Gradle 2.14.1 + JDK 8 可解析但卡死 URL；**可编译组合 = anatawa12 fork `com.anatawa12.forge:ForgeGradle:1.2-1.1.1`（Maven Central）+ Gradle 4.10.3 + JDK 8**（fork 明确拒绝 Gradle 3 及以下：`Gradle 3.x or older is not supported. Please upgrade to 4.x or later`）；组合约束见 [pitfalls A5](../../../pitfalls.md) |

## 时代特征（影响实现的公开常识）

- [常识] 资源体系为 1.8 前老制：无 blockstate / 模型 JSON，方块变体走 metadata，材质走旧贴图注册。
- [常识] 类名 / 方法名是 MCP / SRG 体系，与现代 Mojmap / Yarn 知识不可类比；查名用 Linkie 的 MCP 命名空间。
- [常识] 注册、网络、渲染、存档 API 均为前现代体系——现代版本的写法**一律不可迁移**，以反编译与对应年代官方文档为准。
- [常识] 物品数据为裸 NBT 时代；Java 8 语法上限。
- [实测] 包名前缀 `cpw.mods.fml.*`（如 `cpw.mods.fml.common.eventhandler.SubscribeEvent`），事件在 `net.minecraftforge.event.*`（无 `net.minecraftforge.eventbus`，那是 1.13+）；`net.minecraft.util.ResourceLocation` 两参构造存在；`EnumActionResult`（1.9+）与 `net.minecraft.world.InteractionResult`（1.14+）、`net.minecraft.resources.ResourceLocation`（1.20.5+）均不存在。
- [实测 2026-10-01·javac] 上条已过真实 1.7.10 classpath 编译验证：正例探针（两参 `ResourceLocation` 构造 + `cpw.mods.fml` 的 `@SubscribeEvent` + 外层 `PlayerInteractEvent` 订阅 + `MinecraftForge.EVENT_BUS.post`）BUILD SUCCESSFUL；反例（`EnumActionResult` / `net.minecraft.world.InteractionResult` / `net.minecraft.resources`）3/3 命中"找不到符号/程序包不存在"。证据：`skill-practice/1.7.10-forge/`（r6.log / badprobe.log / probe-green.log）。
- [实测] `PlayerInteractEvent` 无 RightClickBlock 等嵌套子类（1.8 才拆分），直接 import 外层类订阅。

## 已验证经验

- [实测] **插件 id 必须用短 id `apply plugin: 'forge'`**：FG 1.2 系（含 anatawa12 fork 1.1.1）jar 内 `META-INF/gradle-plugins/` 只注册 `forge`/`fml`/`cauldron` 等短 id；`net.minecraftforge.gradle.forge` 报 `Plugin with id ... not found`（新式 id 是 FG 2.0+ 才引入）。
- [实测] **不要用原版 `net.minecraftforge.gradle:ForgeGradle:1.2-SNAPSHOT`**：其下载任务硬编码已死的 Mojang S3 桶，`setupCIWorkspace` 必挂 `:downloadClient` → `java.io.FileNotFoundException: http://s3.amazonaws.com/Minecraft.Download/versions/1.7.10/1.7.10.jar`。anatawa12 fork 已改走 piston-meta/launchermeta 现行 URL。
- [实测] **mappings 现网可用值**（Forge maven `de/oceanlabs/mcp/` 元数据核查）：snapshot 渠道最新 `snapshot_20140925`；stable 渠道最新 `stable_12`（坐标 `12-1.7.10`）。流传的 `snapshot_20141130` 等晚于 20140925 的 1.7.10 snapshot 在现 Forge maven 上不存在（HEAD 404）。
- [实测] **Forge 版本号**：`1.7.10-10.13.4.1614-1.7.10` userdev 存在（HEAD 200），无需回退构建号。
- [实测] **Forge maven 间歇 Read timed out 的根治法**：FG 注入的 forge-maven 排在仓库链首位且传输错误不回退到后续仓库，逐件重试极慢。在 build.gradle 的 repositories 块里 `repositories.clear()` 后按 `mavenCentral → aliyun → forge maven` 重排：MC 运行库（scala、jsr305、guava 等）全部从 Central 命中绕开 flaky 源；仅 Central 没有的（如 `net.minecraft:launchwrapper`）仍走 Forge maven。清单三件套超时/重试 systemProp（300s/5 次）务必保留。
- [实测·已闭环 2026-10-01] `setupCIWorkspace` 12 任务全部执行通过（downloadClient/Server、extractUserDev、genSrgs 等 4 分钟级），**`compileJava` 已闭环**。最后一公里是 Mojang 自有库（`com.mojang:authlib/realms`、`com.ibm.icu:icu4j-core-mojang`、`com.paulscode:*`、`lzma:lzma`、`tv.twitch:*` 共 12 个坐标 + 5 个 twitch natives 分类器）——Central/Aliyun 均无、FG 独立配置只认 Forge maven 且间歇超时。**解法 = 本地 file 仓库**：curl（curl 对各源都秒回，Gradle 才超时）从 `libraries.minecraft.net` 把 pom+jar 连真货收割进 `skill-practice/local-maven/`（pom 404 就合成最小 pom），插到项目仓库链**首位**；两个特殊件：`twitch-external-platform-4.5-natives-windows-64.jar` **官方从未发布**（dev.json 列了但源上没有），与 twitch 系缺 jar 时需**合成最小合法 zip jar**。之后正例探针 BUILD SUCCESSFUL（25s）、反例 3/3 命中、正例还原后 `--offline` 复编译 EXIT=0。local-maven 与合成件已就位，工程幂等可复跑。
- [实测 2026-10-01·skyislands 移植（主世界运行时验证 + 存档 region 13/15）] 实现 = `WorldType` + `VoidChunkProvider`(IChunkProvider) + `PlainsChunkManager`(WorldChunkManager)；下界经 `DimensionManager.registerProviderType(-1, VoidWorldProviderHell)` 条件换生成器；一次性标记 = `MapStorage` WorldSavedData（岛 overworld、下界 DIM1）；1.7.10 RCON 无 `testforblock/execute`（1.8+ 才有）——方块断言走存档 region 解析（服务器写盘即服务器权威）；NBT 用数字物品 id（熔岩桶=327、冰=79）。**关键坑：1.7.10 EventBus 无静态 Class 分支——`register(SomeClass)` 注册实例方法 = 扫描 java.lang.Class 自身、静默零监听器**（实例方法必须注册实例，见 pitfalls B6）；本模组 NetherArrival 初版即踩此坑（下界平台成死代码），已修为单例实例注册。工具链：ForgeGradle 1.2 fork（anatawa12）+ Gradle 2.14.1 + JDK8 + MCP `stable_12`；1.7.10 universal jar 不带 bootstrap，需手铺 vanilla server.jar（sha1 校验）+ launchwrapper-1.12 + asm-all-5.0.3 + jopt-simple-4.5 + lzma-0.0.1，主类 `cpw.mods.fml.relauncher.ServerLaunchWrapper`。mineflayer 最低支持 1.8.8——1.7.10 无 bot 路线。
