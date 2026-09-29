# Minecraft 1.21.9

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**（`[公开文档：…]` 条目以注明来源为准）；API 差异动手前仍须按 api-verification.md 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 21 |
| 发布线 | 1.21.x（The Copper Age，2025-09-30） |
| Loader | NeoForge 21.9.x（版本号镜像 MC 线，以 maven.neoforged.net 实际为准）；Forge（版本线 `[未核实]`，以 files.minecraftforge.net 为准）；Fabric；Quilt（跟进情况 `[未核实]`） |
| 映射 | Mojmap、Yarn、Parchment 均可用 |
| 构建系统 | ModDevGradle（NeoForge 侧）；Fabric Loom（Fabric / Quilt 侧，1.21.x 目标钉 1.17.21——Loom 1.18+ 要求 JVM 25，见 [pitfalls A1](../../../pitfalls.md)） |

## 时代特征（影响实现的公开常识）

- **[实测：E2E 行为测试，见 e2e-testing]** 出生区块概念整体移除（`spawnChunkRadius` 规则删除，1.21.9 起）：远距离方块探测前先 `/forceload`。
- [常识] `InteractionResult` 新模型：`SUCCESS_SERVER` 已拆分（1.21.2+），纯服务端动作用 `SUCCESS_SERVER`——用错编译能过但行为错。
- [公开文档：NeoForge 1.21.9 升级 primer] 资源定位类仍为 `net.minecraft.resources.ResourceLocation`（改名发生在 1.21.11）；`ContainerUser` 抽象自本版本引入。
- [常识] 数据组件模型：物品自定义存储用组件。

## 已验证经验

- 本版本夹在 [1.21.1 ↔ 1.21.11 已验证差异对](../1.21.1/vs-1.21.11-mojmap.md) 之间：从任一端迁移时先读该差异文件作候选清单，逐条核实适用边界（文件内有标注）。
- 本组织在该版本线上有 spawn chunk 行为的 E2E 实测经验（见上与 [e2e-testing](../../../e2e-testing.md)）。
