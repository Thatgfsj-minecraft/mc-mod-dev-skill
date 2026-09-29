# Minecraft 1.21.8

> 可信级别声明：本卡除「已验证经验」外均为**结构事实 / 公开常识**；任何 API 差异动手前必须按 [api-verification](../../../api-verification.md) 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 21 |
| 发布线 | 1.21.x |
| Loader | NeoForge 21.8.x；Fabric；Quilt |
| 映射 | Mojmap、Yarn、Parchment 均可用 |
| 构建系统 | ModDevGradle（NeoForge 侧）；Fabric Loom（Fabric / Quilt 侧） |

## 时代特征（影响实现的公开常识）

- **数据组件模型**：物品自定义存储用组件。
- `InteractionResult` 新模型：`SUCCESS_SERVER` 已拆分（1.21.2+），纯服务端动作用 `SUCCESS_SERVER`。
- `ResourceLocation` → `Identifier` 的改名落在 1.21.1 → 1.21.11 之间，**本版本用哪个未逐点验证**：以项目依赖的 sources jar 为准。

## 已验证经验

暂无。按 [versions/README](../README.md) 的"新增版本"流程沉淀。
