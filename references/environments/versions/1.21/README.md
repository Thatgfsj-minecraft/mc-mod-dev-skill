# Minecraft 1.21

> 可信级别声明：本卡除「已验证经验」外均为**结构事实 / 公开常识**；任何 API 差异动手前必须按 [api-verification](../../../api-verification.md) 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 21 |
| 发布线 | 1.21（Tricky Trials） |
| Loader | NeoForge 21.0.x；Forge；Fabric；Quilt |
| 映射 | Mojmap、Yarn、Parchment 均可用 |
| 构建系统 | ModDevGradle（NeoForge 侧）；Fabric Loom（Fabric / Quilt 侧） |

## 时代特征（影响实现的公开常识）

- **数据组件模型**（1.20.5 起引入）：物品自定义存储用组件。
- `InteractionResult` 旧模型：`SUCCESS_SERVER` 拆分发生在 1.21.2+，本版本纯服务端动作仍用 `SUCCESS`。
- 资源定位类为 `net.minecraft.resources.ResourceLocation`。

## 已验证经验

暂无。按 [versions/README](../README.md) 的"新增版本"流程沉淀。
