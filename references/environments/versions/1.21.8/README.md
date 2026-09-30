# Minecraft 1.21.8

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**（`[公开文档：…]` 条目以注明来源为准）；API 差异动手前仍须按 api-verification.md 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 21 |
| 发布线 | 1.21.x |
| Loader | NeoForge 21.8.x；Forge 58.x（公开版本线，非本组织验证）；Fabric；Quilt（跟进情况 `[未核实]`） |
| 映射 | Mojmap、Yarn、Parchment 均可用 |
| 构建系统 | ModDevGradle（NeoForge 侧）；Fabric Loom（Fabric / Quilt 侧，1.21.x 目标钉 1.17.21——Loom 1.18+ 要求 JVM 25，见 [pitfalls A1](../../../pitfalls.md)） |
| 已验证依赖坐标 | fabric-api `0.136.1+1.21.8`、fabric-loader 0.19.3（[实测：2026-09-30 skyislands 1.21.8-fabric 全量构建+服务器冒烟]）；NeoForge `21.8.54`（[实测：ModDevGradle 2.0.147 全量构建+首跑 NFRT 25 分钟]） |

## 时代特征（影响实现的公开常识）

- [常识] 数据组件模型：物品自定义存储用组件。
- [常识 → 实测] `InteractionResult` 新模型：`SUCCESS_SERVER` 已拆分（1.21.2+），纯服务端动作用 `SUCCESS_SERVER`——用错编译能过但行为错。**`InteractionResult.SUCCESS_SERVER` 静态字段 [实测] 存在**。
- [公开文档：NeoForge 升级 primer → 实测] 资源定位类仍为 `net.minecraft.resources.ResourceLocation`（改名发生在 1.21.11）；`ResourceLocation.fromNamespaceAndPath(ns, path)` [实测] 可用。`net.minecraft.resources.Identifier` [实测] 不存在。
- [实测] `DimensionType.bedWorks()` 为**实例方法**（方法引用 `DimensionType::bedWorks` 编译通过，签名 `() -> boolean`）；1.21.11 才被环境属性系统取代。
- [实测] `net.minecraft.world.entity.ContainerUser` **不存在**——该抽象 1.21.9 才引入，本版本容器回调仍是 `startOpen(Player)` / `stopOpen(Player)`（Player 端见 [1.21.1 ↔ 1.21.11 差异对 §3](../1.21.1/vs-1.21.11-mojmap.md)）。

## 已验证经验

- 本版本夹在 [1.21.1 ↔ 1.21.11 已验证差异对](../1.21.1/vs-1.21.11-mojmap.md) 之间：从任一端迁移时先读该差异文件作候选清单，逐条核实适用边界（文件内有标注）。
- [实测：编译探针，2026-09-30，工程 skill-practice/1.21.8-fabric] Fabric + Mojmap 工具链：JDK 21（Corretto）+ Gradle wrapper 9.5.1 + fabric-loom 1.17.21 + minecraft 1.21.8 + officialMojangMappings + fabric-loader 0.19.5，`compileJava` 一次通过（首跑含 1.21.8 依赖冷下载未单独计时，热重编译 4 s）。探针逐条结论：
  - `net.minecraft.resources.ResourceLocation` + `fromNamespaceAndPath("a","b")` 编译通过 → RL 存在，工厂方法可用；
  - `InteractionResult.SUCCESS_SERVER` 静态引用编译通过 → 字段存在；
  - `Predicate<DimensionType> p = DimensionType::bedWorks;` 编译通过 → `bedWorks()` 为实例方法；
  - 加 `import net.minecraft.world.entity.ContainerUser;` 编译失败：`错误: 找不到符号`（指向该 import）→ ContainerUser 在 1.21.8 不存在，**引入边界 = 1.21.9 实测成立**（1.21.9 探针 skill-practice/1.21.9-fabric 同写法通过）；
  - 加 `import net.minecraft.resources.Identifier;` 编译失败：`错误: 找不到符号`（指向该 import）→ Identifier 在 1.21.8 不存在（改名边界 = 1.21.11，与 primer 记载一致）。
- [实测·经验] 从 1.21.9 探针工程整体拷贝 + 仅改 build.gradle 版本行 / settings.gradle 工程名，loom 配置零调整即可跑通 1.21.8——同 Loom 1.17.21 + Gradle 9.5.1 服务 1.21.x 各版本，跨小版本探针复用成本极低。
- [实测：2026-09-30 skyislands 移植（四构建编译 + Fabric 专用服务器冒烟 + mineflayer bot E2E 13/13 全绿）] 与 1.21.9 的 API 分界（皆见 [vs 文件 §2/§5/§6/§7](../1.21.1/vs-1.21.11-mojmap.md)）：本版本仍在 `ResourceLocation` 侧、无 `ContainerUser`、`SUCCESS_SERVER` 已拆分；**SavedData 只剩 `SavedDataType` codec 一条路**（基类无抽象 `save()`、`Factory` 已删除，1.21.1 式迁移直接编译失败）；出生点仍用 `ServerLevel.setDefaultSpawnPos`（无 `setRespawnData`）；noise_router 字段仍为 `initial_density_without_jaggedness`；`CompoundTag.getBoolean` 返回 `Optional<Boolean>`。行为侧：建岛/下界虚空/首进下界一次性萤石平台全部实测通过。证据：mapped jar javap/unzip + `sky-islands-test/server-b-1218` 冒烟记录。
