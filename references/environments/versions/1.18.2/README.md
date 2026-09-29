# Minecraft 1.18.2

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**（`[公开文档：…]` 条目以注明来源为准）；API 差异动手前仍须按 api-verification.md 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 17 |
| 发布线 | 1.18.x（Caves & Cliffs Part II；1.17 起进入 Java 16/17 + 官方映射时代） |
| Loader | Forge 40.x（公开版本线，非本组织验证）；Fabric；Quilt；无 NeoForge（1.20.1 才分叉） |
| 映射 | Mojmap（official，ForgeGradle 主流配置）、Yarn、Parchment 均可用 |
| 构建系统 | ForgeGradle 5.x（Forge 侧）；Fabric Loom（Fabric / Quilt 侧） |

## 时代特征（影响实现的公开常识）

- [常识] Flattening 后现代资源体系；blockstate / 模型 JSON。
- [常识] 物品数据裸 NBT（数据组件 1.20.5 才引入）。
- [常识] `InteractionResult` 旧模型（`SUCCESS_SERVER` 1.21.2+ 才拆分）；Mojmap 语境下资源定位类为 `ResourceLocation`。

## 已验证经验

- 暂无。按 [versions/README](../README.md) 的"新增版本"流程沉淀。
