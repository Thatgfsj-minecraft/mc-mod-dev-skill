# Minecraft 1.21.5

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**（`[公开文档：…]` 条目以注明来源为准）；API 差异动手前仍须按 api-verification.md 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 21 |
| 发布线 | 1.21.x |
| Loader | NeoForge 21.5.x；Forge 55.x（公开版本线，非本组织验证）；Fabric；Quilt（跟进情况 `[未核实]`） |
| 映射 | Mojmap、Yarn、Parchment 均可用 |
| 构建系统 | ModDevGradle（NeoForge 侧）；Fabric Loom（Fabric / Quilt 侧，1.21.x 目标钉 1.17.21——Loom 1.18+ 要求 JVM 25，见 [pitfalls A1](../../../pitfalls.md)） |
| 已验证依赖坐标 | fabric-api `0.128.2+1.21.5`、fabric-loader 0.19.3、NeoForge `21.5.98`（[实测：2026-09-30 skyislands 四构建全量编译 + Fabric 服务器冒烟 + bot E2E 全绿]） |

## 时代特征（影响实现的公开常识）

- [常识] 数据组件模型：物品自定义存储用组件。
- [常识] `InteractionResult` 新模型：`SUCCESS_SERVER` 已拆分（1.21.2+），纯服务端动作用 `SUCCESS_SERVER`——用错编译能过但行为错。
- [公开文档：NeoForge 升级 primer] 资源定位类仍为 `net.minecraft.resources.ResourceLocation`（改名发生在 1.21.11）；动手前可用 sources jar 一键核实。

## 已验证经验

- 本版本夹在 [1.21.1 ↔ 1.21.11 已验证差异对](../1.21.1/vs-1.21.11-mojmap.md) 之间：从任一端迁移时先读该差异文件作候选清单，逐条核实适用边界（文件内有标注）。
- [实测：2026-09-30 skyislands 移植（四构建编译 + Fabric 专用服务器冒烟 + mineflayer bot E2E 全绿）] **`SavedDataType`（codec 式存储）在本版本已引入**——1.21.4 无、1.5 有，引入边界 = 1.21.5（不是公开资料常引的 1.21.6；详见 [vs 文件 §7](../1.21.1/vs-1.21.11-mojmap.md)），1.21.1 式 `SavedData.Factory` 在本版本无法编译。其余仍走 1.21.1 式：`setDefaultSpawnPos`、noise_router 字段 `initial_density_without_jaggedness`、`ResourceLocation`。数据包 tag 路径仍必须是 `data/minecraft/tags/...`（`data/tags/...` 会让服务器拒启，见 pitfalls C4）。证据：mapped jar javap/unzip + `sky-islands-test/server-a2-1215` 冒烟记录。
