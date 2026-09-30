# Minecraft 1.21.11

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**（`[公开文档：…]` 条目以注明来源为准）；API 差异动手前仍须按 api-verification.md 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 21（`[实测]` 组织构建用 JDK 21；Temurin / Corretto 均可；`fabric.mod.json` 的 `depends.java` 应声明 `>=21`） |
| 发布线 | 1.21.x（Mounts of Mayhem） |
| Loader | NeoForge 21.11.x（`[实测]` 组织构建用过 21.11.45）；Forge 61.x（公开版本线，非本组织验证）；Fabric；Quilt（跟进情况 `[未核实]`） |
| 映射 | Mojmap（`[实测]` 组织构建用官方映射）、Yarn、Parchment 均可用 |
| 构建系统 | ModDevGradle 2.x（NeoForge 侧，`[实测]` 2.0.147）；Fabric Loom（Fabric / Quilt 侧，1.21.x 目标钉 1.17.21——见 [pitfalls A1](../../../pitfalls.md)） |
| Gradle | 9.5.1（`[实测]` wrapper） |
| 已验证依赖坐标 | fabric-api `0.141.6+1.21.11`；fabric-loader 构建期 `0.19.3`、`0.19.5`（后者 `[实测]` 且为 meta 首个稳定版） |

## 时代特征（影响实现的公开常识）

- [常识] 数据组件模型：物品自定义存储用组件。
- [实测：编译探针] `InteractionResult` 新模型：纯服务端动作用 `SUCCESS_SERVER`——用错编译能过但行为错。
- [实测：编译探针 + mappings 复核] 资源定位类已改名 `net.minecraft.resources.Identifier`，静态工厂 `Identifier.fromNamespaceAndPath(ns, path)` 存在（包在 `resources`，以此区分 Yarn 的 `net.minecraft.util.Identifier`）。
- [实测：编译探针 + mappings 复核] 容器开关回调为 `ContainerUser` 抽象（`net.minecraft.world.entity.ContainerUser`，1.21.9 引入，非本版本才引入）。
- [公开文档：NeoForge 1.21.11 primer] 维度能力判定改环境属性系统（`DimensionType.bedWorks()` 已移除，改 `EnvironmentAttributes.BED_RULE`）。
- **[实测：E2E 行为测试，见 e2e-testing]** 出生区块概念整体移除（`spawnChunkRadius` 规则删除，1.21.9 起）：远距离方块探测前先 `/forceload`。

## 已验证经验

- [1.21.1 ↔ 1.21.11 差异对照（Mojmap）](../1.21.1/vs-1.21.11-mojmap.md)：从 1.21.1 迁移视角逐条列出（组织四构建实测，含各条目适用边界）。
- [实测：编译探针 + mappings 复核（2026-09-30，正向验证）] 工程 `skill-practice/1.21.11-fabric`（JDK 21 + Loom 1.17.21 + Gradle 9.5.1 wrapper + `options.release=21` + loader 0.19.5，loom 缓存热，单次 compileJava 约 3–6 秒）。逐条结论：
  - `net.minecraft.resources.Identifier` + `Identifier.fromNamespaceAndPath(ns, path)` ✔ 存在，与公开资料一致。
  - `net.minecraft.world.entity.ContainerUser` ✔ 存在，与公开资料一致。
  - `InteractionResult.SUCCESS_SERVER` ✔ 静态字段存在，与公开资料一致。
  - `SavedDataType` ✔ 存在但**两处与公开资料不符**：① 包路径是 `net.minecraft.world.level.saveddata`（不是 `world.level.storage`）；② record 4 参构造器实际为 `(String id, Supplier<T> constructor, Codec<T> codec, DataFixTypes dataFixType)`——第 3 参是 Mojang `Codec`（非 `StreamCodec`），第 4 参是 `DataFixTypes`（非 `int version`）。
  - `MinecraftServer.setRespawnData(LevelData.RespawnData)` ✔ 方法引用 `BiConsumer<MinecraftServer, LevelData.RespawnData>` 编译通过，void 返回；注意 `LevelData$RespawnData` 在 `net.minecraft.world.level.storage` 下。另有 `Level.setRespawnData` 同名方法，勿混淆。
  - `NoiseRouter.preliminarySurfaceLevel()` ✔ record 组件存在（comp_4450），但返回类型是 **`DensityFunction`**（`net.minecraft.world.level.levelgen.DensityFunction`），不是 `double`。
  - 复核手法：编译失败/存疑时直接 grep loom 缓存 `mappings.tiny`（`GRADLE_USER_HOME/caches/fabric-loom/1.21.11/loom.mappings.*`），比解包映射 jar 更快且含完整方法描述符。
