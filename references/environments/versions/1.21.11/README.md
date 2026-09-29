# Minecraft 1.21.11

> 可信级别声明：本卡除「已验证经验」外均为**结构事实 / 公开常识**；任何 API 差异动手前必须按 [api-verification](../../../api-verification.md) 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 21（组织已验证构建用 JDK 21） |
| 发布线 | 1.21.x |
| Loader | NeoForge 21.11.x（已验证构建用过 21.11.45）；Fabric；Quilt |
| 映射 | Mojmap（组织已验证构建用官方映射）、Yarn、Parchment 均可用 |
| 构建系统 | ModDevGradle 2.x（NeoForge 侧）；Fabric Loom（Fabric / Quilt 侧，1.21.x 目标钉 1.17.21——见 [pitfalls A1](../../../pitfalls.md)） |
| 已验证依赖坐标 | fabric-api `0.141.6+1.21.11`；fabric-loader 构建期 `0.19.3` |

## 时代特征（影响实现的公开常识）

- **数据组件模型**：物品自定义存储用组件。
- `InteractionResult` 新模型：纯服务端动作用 `SUCCESS_SERVER`。
- 资源定位类已改名 `net.minecraft.resources.Identifier`（包仍在 `resources`，以此区分 Yarn 的 `net.minecraft.util.Identifier`）。
- 容器开关回调引入 `ContainerUser` 抽象。
- 维度能力判定改环境属性系统（`DimensionType.bedWorks()` 已移除，改 `EnvironmentAttributes.BED_RULE`）。
- 出生区块默认不加载（实测 1.21.9+）：远距离方块探测前先 `/forceload`（见 [e2e-testing](../../../e2e-testing.md)）。

## 已验证经验

- [1.21.1 ↔ 1.21.11 差异对照（Mojmap）](../1.21.1/vs-1.21.11-mojmap.md)：从 1.21.1 迁移视角逐条列出（组织四构建实测）。
