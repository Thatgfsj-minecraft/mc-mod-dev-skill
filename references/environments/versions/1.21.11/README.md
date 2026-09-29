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
| 已验证依赖坐标 | fabric-api `0.141.6+1.21.11`；fabric-loader 构建期 `0.19.3`（均为 `[实测]`） |

## 时代特征（影响实现的公开常识）

- [常识] 数据组件模型：物品自定义存储用组件。
- [常识] `InteractionResult` 新模型：纯服务端动作用 `SUCCESS_SERVER`——用错编译能过但行为错。
- [公开文档：NeoForge primer，组织四构建实测佐证] 资源定位类已改名 `net.minecraft.resources.Identifier`（包仍在 `resources`，以此区分 Yarn 的 `net.minecraft.util.Identifier`）。
- [公开文档：NeoForge 1.21.9 primer] 容器开关回调为 `ContainerUser` 抽象（1.21.9 引入，非本版本才引入）。
- [公开文档：NeoForge 1.21.11 primer] 维度能力判定改环境属性系统（`DimensionType.bedWorks()` 已移除，改 `EnvironmentAttributes.BED_RULE`）。
- **[实测：E2E 行为测试，见 e2e-testing]** 出生区块概念整体移除（`spawnChunkRadius` 规则删除，1.21.9 起）：远距离方块探测前先 `/forceload`。

## 已验证经验

- [1.21.1 ↔ 1.21.11 差异对照（Mojmap）](../1.21.1/vs-1.21.11-mojmap.md)：从 1.21.1 迁移视角逐条列出（组织四构建实测，含各条目适用边界）。
