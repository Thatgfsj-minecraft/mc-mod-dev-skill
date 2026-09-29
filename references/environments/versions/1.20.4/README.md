# Minecraft 1.20.4

> 可信级别声明：本卡除「已验证经验」外均为**结构事实 / 公开常识**；任何 API 差异动手前必须按 [api-verification](../../../api-verification.md) 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 17 |
| 发布线 | 1.20.x（Bats and Pots；1.20.3 / 1.20.4 同期） |
| Loader | Forge 49.x；NeoForge 20.4.x（1.20.2 起 NeoForge 为独立代码线）；Fabric；Quilt |
| 映射 | Mojmap、Yarn、Parchment 均可用 |
| 构建系统 | ForgeGradle / NeoGradle（NeoForge 侧）；Fabric Loom（Fabric / Quilt 侧） |

## 时代特征（影响实现的公开常识）

- 物品自定义数据用**任意 NBT**（数据组件 1.20.5 才引入）。
- `InteractionResult` 旧模型：`SUCCESS_SERVER` 拆分发生在 1.21.2+，本版本纯服务端动作仍用 `SUCCESS`。
- 资源定位类为 `net.minecraft.resources.ResourceLocation`（改名发生在 1.21.11）。
- 1.20.2 起网络 / 注册 API 相对 1.20.1 有调整（`[未核实]` 未逐条验证，以反编译为准）。

## 已验证经验

暂无。按 [versions/README](../README.md) 的"新增版本"流程沉淀。
