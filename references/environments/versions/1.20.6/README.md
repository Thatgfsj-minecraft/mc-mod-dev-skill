# Minecraft 1.20.6

> 可信级别声明：本卡除「已验证经验」外均为**结构事实 / 公开常识**；任何 API 差异动手前必须按 [api-verification](../../../api-verification.md) 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 21（1.20.5 起由 17 提升） |
| 发布线 | 1.20.5 / 1.20.6 同期 |
| Loader | NeoForge 20.6.x（独立线）；Forge；Fabric；Quilt |
| 映射 | Mojmap、Yarn、Parchment 均可用 |
| 构建系统 | ModDevGradle / NeoGradle（NeoForge 侧）；Fabric Loom（Fabric / Quilt 侧） |

## 时代特征（影响实现的公开常识）

- **数据组件已引入（1.20.5）**：物品自定义存储从任意 NBT 迁移到组件模型；物品数据功能按组件设计，NBT 直接读写不再适用。
- `InteractionResult` 旧模型：`SUCCESS_SERVER` 拆分发生在 1.21.2+，本版本纯服务端动作仍用 `SUCCESS`。
- 资源定位类为 `net.minecraft.resources.ResourceLocation`。

## 已验证经验

暂无。按 [versions/README](../README.md) 的"新增版本"流程沉淀。
