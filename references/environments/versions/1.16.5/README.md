# Minecraft 1.16.5

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**（`[公开文档：…]` 条目以注明来源为准）；API 差异动手前仍须按 api-verification.md 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 8（1.17 才提升） |
| 发布线 | 1.16.x（Nether Update；1.16.5 = 1.16.4 + 安全修复，是本线事实上的主流模组目标） |
| Loader | Forge 36.x（公开版本线，非本组织验证；36.2.x 为常见分支）；Fabric / Quilt 可用；无 NeoForge（1.20.1 才分叉） |
| 映射 | MCP（snapshot / stable）为主流；Mojang 官方映射在部分后期工具链可用 `[未核实]` |
| 构建系统 | ForgeGradle 4.x（Forge 侧）；Fabric Loom（Fabric / Quilt 侧） |

## 时代特征（影响实现的公开常识）

- [常识] Flattening 已完成（1.13+）：无 metadata 副方块，blockstate 体系。
- [常识] 物品数据裸 NBT（数据组件 1.20.5 才引入）。
- [常识] `InteractionResult` 旧模型（`SUCCESS_SERVER` 1.21.2+ 才拆分）；Mojmap 语境下资源定位类为 `ResourceLocation`。
- [常识] Java 8 语法上限。

## 已验证经验

- 暂无。按 [versions/README](../README.md) 的"新增版本"流程沉淀。
