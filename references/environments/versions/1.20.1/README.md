# Minecraft 1.20.1

> 可信级别声明：本卡除「已验证经验」外均为**结构事实 / 公开常识**；任何 API 差异动手前必须按 [api-verification](../../../api-verification.md) 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 17 |
| 发布线 | 1.20（Trails & Tales） |
| Loader | Forge 47.x；NeoForge 47.1.x（1.20.1 分叉点，与 Forge 基本同源）；Fabric；Quilt |
| 映射 | Mojmap、Yarn、Parchment 均可用 |
| 构建系统 | ForgeGradle（Forge / NeoForge 侧）；Fabric Loom（Fabric / Quilt 侧） |

## 时代特征（影响实现的公开常识）

- 物品自定义数据用**任意 NBT**（数据组件 1.20.5 才引入）；以 NBT 为存储的物品功能按 NBT 模型实现。
- `InteractionResult` 旧模型：`SUCCESS_SERVER` 拆分发生在 1.21.2+，本版本纯服务端动作仍用 `SUCCESS`。
- 资源定位类为 `net.minecraft.resources.ResourceLocation`（改名发生在 1.21.11，见 NeoForge 升级 primer）。

## 已验证经验

暂无。按 [versions/README](../README.md) 的"新增版本"流程沉淀。
