# Minecraft 1.12.2

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**（`[公开文档：…]` 条目以注明来源为准）；API 差异动手前仍须按 api-verification.md 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 8 |
| 发布线 | 1.12.x（World of Color Update；1.7.10 之后存量 mod 最大的长线版本之一） |
| Loader | Forge 14.23.x（14.23.5.x 为事实标准分支）；LiteLoader 存在；NeoForge / Fabric / Quilt 不支持（Legacy Fabric 非主流 `[未核实]`） |
| 映射 | MCP（stable_39 为本线终版稳定映射） |
| 构建系统 | ForgeGradle 2.x（官方 MDK；社区有后期工具链移植 `[未核实]`）+ JDK 8 + 老 Gradle（组合约束见 [pitfalls A5](../../../pitfalls.md)）；[实测·部分受阻 2026-10-01] 成熟组合 Gradle **4.10.3** + ForgeGradle **2.3-SNAPSHOT（解析为 20210802.170449-48）** + JDK8 路径 `O:\clawwork\tools\jdk1.8.0_504`，插件解析已跑通，见下"已验证经验" |

## 时代特征（影响实现的公开常识）

- [常识] metadata 副方块仍在（Flattening 发生在 1.13）。
- [常识] 注册 / 事件为 Forge 旧 API 体系，与 NeoForge 现代写法不同代。
- [常识] 物品数据裸 NBT；类名 / 方法名为 MCP / SRG 体系；Java 8 语法上限。
- [常识] 现代版本的任何写法（注册、网络、渲染、数据存储）都不可迁移到本版本，一律以反编译与对应年代官方文档为准。
- [实测 2026-10-01·javac] MCP 名可编译（FG2 userdev 2847 + snapshot_20180814 映射，classpath 就位后 javac 零报错）：`new ResourceLocation("a", "b")` **两参构造存在**（`net.minecraft.util.ResourceLocation`）、`net.minecraft.util.EnumActionResult.SUCCESS` **存在**、`@SubscribeEvent` + `PlayerInteractEvent.RightClickBlock`（嵌套事件类）可作 handler 签名。证据：`skill-practice/1.12.2-forge/build-log9.txt`（BUILD SUCCESSFUL）。
- [实测 2026-10-01·javac·反例] 现代名（1.19+/Mojmap 布局）在本 classpath **不存在**：BadProbe 三连 `--offline` 编译 3/3 命中预期（证据 `skill-practice/1.12.2-forge/build-log12.txt`，BUILD FAILED in 29s）：`net.minecraft.world.InteractionResult` → `错误: 找不到符号 类 InteractionResult`；`net.minecraft.world.level.dimension.DimensionType` → `错误: 程序包net.minecraft.world.level.dimension不存在`；`net.minecraft.resources.ResourceLocation` → `错误: 程序包net.minecraft.resources不存在`。1.12.2 时代正确布局为 `net.minecraft.util.ResourceLocation` / `net.minecraft.world.DimensionType`（Flattening 前）。正例 `Probe.java` 还原后 `--offline` 复编译 BUILD SUCCESSFUL in 29s（build-log13.txt）。**经验：缓存全后用 `--offline` 可完全绕开 forge maven 陈旧连接问题**。
- [实测·探针自身坑] 嵌套类导入不引外层名进作用域：`import net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock;` 后签名只能写 `RightClickBlock`，写 `PlayerInteractEvent.RightClickBlock` 会报 `错误: 程序包PlayerInteractEvent不存在`（javac 中文环境报错原文，Probe.java 首版踩中，已修）。

## 已验证经验（2026-10-01 老线考古轮，工程 `skill-practice/1.12.2-forge/`，总耗时约 30 分钟）

- [实测] **FG2 必须用 Forge `14.23.5.2847`，不能用末版 2859**：`1.12.2-14.23.5.2859` 在 maven.minecraftforge.net 上**没有 `-userdev.jar`（404）**（universal 4,466,108 B 在），FG2 解析 `forge-userdev.jar` 直接失败；`14.23.5.2847` 的 userdev（5,309,560 B）与 universal 均存在。
- [实测] Gradle 4.10.3 发行包：**TUNA `mirrors.tuna.tsinghua.edu.cn/gradle/` 下 4.10.3-bin.zip 已 404（老版本被清）**；腾讯镜像 `mirrors.cloud.tencent.com/gradle/gradle-4.10.3-bin.zip` 可下（78,422,006 B 完整）。
- [实测] JDK8（`O:\clawwork\tools\jdk1.8.0_504`）+ Gradle 4.10.3 启动正常；`gradle.properties` 写 `org.gradle.java.home=O:/clawwork/tools/jdk1.8.0_504`（正斜杠即可，无需转义）+ 进程级 `JAVA_HOME` 双保险可用。
- [实测] maven.minecraftforge.net **可达但慢**：首次解析 FG 2.3-SNAPSHOT 时 transitive 依赖 `com.github.tony19:named-regexp:0.2.3` `Read timed out`（报错原文见下），重跑即成功；aliyun public 镜像与 Maven Central 均有该包（三家 HEAD 均 200）。buildscript 仓库顺序 aliyun → forge → central 可提高稳度。
- [实测] **FG 2.3-SNAPSHOT（2021-08 build）已现代化**：MC version manifest 走 `piston-meta.mojang.com`（本机可达），1.12.2 的 client/server jar URL 指向 `piston-data.mojang.com`、库走 `libraries.minecraft.net`（root 探测 404 = host 可达），未复现 launchermeta 阻断问题。
- [实测·受阻→2026-10-01 补完轮已通] `setupCIWorkspace` 资源准备全部完成：上轮超时实为**下载在后台延续完成**（client/server jar、merged jar、userdev 解包、McManifest/McpMappings/versionJson 均已在共享缓存 `chuansongmen/.gradle-home/caches/minecraft/` 落地）；后续只需 `gradle setupCIWorkspace compileJava`，已缓存步骤全部 SKIPPED/UP-TO-DATE。**compileJava 探针已执行（见上"时代特征"两条 [实测·javac]）**。
- [实测 2026-10-01·关键坑] **Gradle 访问 maven.minecraftforge.net 间歇 `Read timed out`（curl 同 URL 秒回 200），失败会拖死整条构建但已下载工件留在缓存**——每次重跑净推进。四个对策叠加：(1) `gradle.properties` 写 `systemProp.org.gradle.internal.http.connection.timeout=300000` + `systemProp.org.gradle.internal.http.socket.timeout=300000` + `systemProp.org.gradle.internal.repository.max.tentatives=5`（GRADLE_USER_HOME 与工程各放一份保险）；(2) 失败即原样重跑（典型序列：run1 死于 mcp-srg.zip → run2 死于 icu4j-core-mojang → run3/4 死于 commons-compress HEAD → run5 全绿）；(3) 缺失 MC 依赖（dev.json 里 19 个 library 对比 modules-2 缓存）可**手工 curl 预填充** `modules-2/files-2.1/<group.path>/<artifact>/<version>/<sha1>/`（sha1 取自仓库 `<file>.sha1`，aliyun/central/libraries.minecraft.net 均可作源；注意 files-2.1 裸放文件**不建 metadata 索引**，Gradle 仍会对该模块走网络，预填充只省下载不作解析豁免）；(4) **缓存全后用 `--offline` 彻底绕开网络**——正例/反例编译均 29s 全绿/全红，不再受 forge maven 抽风影响。
- [实测] `setupCIWorkspace` 各步产物路径（续跑判断用）：`caches/minecraft/net/minecraft/minecraft_merged/1.12.2/minecraft_merged-1.12.2.jar` 出现 = mergeJars 完成；`Task :setupCiWorkspace` 出现 = 全链就绪，之后可直接 `gradle compileJava`（42s）。
- [实测] 多代理并行共享 `GRADLE_USER_HOME` 时，Gradle 9.5.1（jars-9 / fabric-loom caches）与老 Gradle 目录互不冲突，可并存。
- [受阻报错原文 1] `Could not resolve com.github.tony19:named-regexp:0.2.3. Required by: net.minecraftforge.gradle:ForgeGradle:2.3-SNAPSHOT:20210802.170449-48 ... > Read timed out`（forge maven 慢，重跑成功）。
- [受阻报错原文 2] `Could not find forge-userdev.jar (net.minecraftforge:forge:1.12.2-14.23.5.2859). Searched in: https://maven.minecraftforge.net/net/minecraftforge/forge/1.12.2-14.23.5.2859/forge-1.12.2-14.23.5.2859-userdev.jar`（换 2847 解决）。
