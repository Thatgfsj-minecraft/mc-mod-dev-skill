# Minecraft 1.21.9

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**（`[公开文档：…]` 条目以注明来源为准）；API 差异动手前仍须按 api-verification.md 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 21 |
| 发布线 | 1.21.x（The Copper Age，2025-09-30） |
| Loader | NeoForge 21.9.x（版本号镜像 MC 线，以 maven.neoforged.net 实际为准）；Forge（版本线 `[未核实]`，以 files.minecraftforge.net 为准）；Fabric；Quilt（跟进情况 `[未核实]`） |
| 映射 | Mojmap、Yarn、Parchment 均可用 |
| 构建系统 | ModDevGradle（NeoForge 侧）；Fabric Loom（Fabric / Quilt 侧，1.21.x 目标钉 1.17.21——Loom 1.18+ 要求 JVM 25，见 [pitfalls A1](../../../pitfalls.md)）[实测：Loom 1.17.21 + Gradle 9.5.1 + JDK 21 冷启动跑通 1.21.9 `compileJava`，见 skill-practice/1.21.9-fabric] |
| 已验证依赖坐标 | fabric-api `0.134.1+1.21.9`、fabric-loader 0.19.3（[实测：2026-09-30 skyislands 1.21.9-fabric 全量构建+服务器冒烟]）；NeoForge **`21.9.16-beta`——21.9 线在 maven.neoforged.net/releases 上没有任何正式版**（[实测：maven-metadata 全量列表 + versions JSON API + 无后缀 pom 直探 404 三重核实，ModDevGradle 2.0.147 正常解析该 beta]） |

## 时代特征（影响实现的公开常识）

- **[实测：E2E 行为测试，见 e2e-testing]** 出生区块概念整体移除（`spawnChunkRadius` 规则删除，1.21.9 起）：远距离方块探测前先 `/forceload`。
- [常识] `InteractionResult` 新模型：`SUCCESS_SERVER` 已拆分（1.21.2+），纯服务端动作用 `SUCCESS_SERVER`——用错编译能过但行为错。
- [实测：探针编译通过，见 skill-practice/1.21.9-fabric]（原标注：公开文档 NeoForge 1.21.9 升级 primer）资源定位类为 `net.minecraft.resources.ResourceLocation`，静态工厂 `fromNamespaceAndPath(String, String)` 存在可用；`ContainerUser`（`net.minecraft.world.entity.ContainerUser`）存在可 import——改名 `Identifier` 发生在 1.21.11，本版本**实测确认不存在** `net.minecraft.resources.Identifier`（探针 import 报错：`错误: 找不到符号 类 Identifier 位置: 程序包 net.minecraft.resources`）。
- [实测：探针编译通过，见 skill-practice/1.21.9-fabric]（原标注：公开文档 NeoForge 1.21.9 升级 primer）`InteractionResult.SUCCESS_SERVER` 为可静态引用的字段；`DimensionType.bedWorks()` 存在但是**实例方法**（静态调用 `DimensionType.bedWorks()` 编译报错"无法从静态上下文中引用非静态 方法 bedWorks()"，须在 `DimensionType` 实例上调用）。
- [常识] 数据组件模型：物品自定义存储用组件。

## 已验证经验

- 本版本夹在 [1.21.1 ↔ 1.21.11 已验证差异对](../1.21.1/vs-1.21.11-mojmap.md) 之间：从任一端迁移时先读该差异文件作候选清单，逐条核实适用边界（文件内有标注）。
- 本组织在该版本线上有 spawn chunk 行为的 E2E 实测经验（见上与 [e2e-testing](../../../e2e-testing.md)）。
- [实测：编译探针，2026-09-30，工程 skill-practice/1.21.9-fabric] 工具链：Gradle 9.5.1 wrapper + fabric-loom 1.17.21 + fabric-loader 0.19.5 + JDK 21（Corretto）+ officialMojangMappings，无 fabric-api，`compileJava` 通过。逐条结论：(1) `ResourceLocation.fromNamespaceAndPath("a","b")` 实测存在；(2) `net.minecraft.world.entity.ContainerUser` 实测可 import；(3) `InteractionResult.SUCCESS_SERVER` 实测为静态字段；(4) `DimensionType.bedWorks()` 实测存在但为实例方法，不能静态调用；(5) `net.minecraft.resources.Identifier` 实测确认不存在（该改名钉在 1.21.11）。冷启动（含下载 1.21.9 依赖与 remap）全程约 6 分钟，未启动游戏/服务器。
- [实测：2026-09-30 skyislands 移植（四构建编译 + Fabric 专用服务器冒烟 + mineflayer bot E2E 全绿）] 存储与出生点 API 已切换到新式（皆见 [vs 文件 §5/§6/§7](../1.21.1/vs-1.21.11-mojmap.md)）：`SavedDataType` codec 式存储可用；`MinecraftServer.setRespawnData(LevelData.RespawnData.of(...))` 可用；noise_router 字段已改名 `preliminary_surface_level`（javap record 组件 + 常量池双证据）；行为侧"首进下界一次性萤石平台"实测通过。证据：mapped jar javap/unzip + `sky-islands-test/server-c-1219` 冒烟记录。
