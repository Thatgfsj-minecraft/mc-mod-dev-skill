# Minecraft 1.21.1

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**（`[公开文档：…]` 条目以注明来源为准）；API 差异动手前仍须按 api-verification.md 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 21（`[实测]` 组织构建用 JDK 21，源码 release=21；Temurin / Corretto 均可；`fabric.mod.json` 的 `depends.java` 应声明 `>=21`） |
| 发布线 | 1.21.x |
| Loader | NeoForge 21.1.x（`[实测]` 组织构建用过 21.1.252）；Forge（版本线 `[未核实]`，以 files.minecraftforge.net 为准）；Fabric；Quilt |
| 映射 | Mojmap（`[实测]` 组织构建用官方映射）、Yarn、Parchment 均可用 |
| 构建系统 | ModDevGradle 2.x（NeoForge 侧，`[实测]` 2.0.147）；Fabric Loom（Fabric / Quilt 侧，1.21.x 目标钉 1.17.21——Loom 1.18+ 要求 JVM 25，见 [pitfalls A1](../../../pitfalls.md)） |
| Gradle | 9.5.1（`[实测]` wrapper） |
| 已验证依赖坐标 | fabric-api `0.116.17+1.21.1`；fabric-loader 构建期 `0.19.3`（均为 `[实测]`） |

## 时代特征（影响实现的公开常识）

- [常识] 数据组件模型：物品自定义存储用组件。
- [常识] `InteractionResult` 旧模型：`SUCCESS_SERVER` 拆分发生在 1.21.2+，本版本纯服务端动作仍用 `SUCCESS`。
- [公开文档：NeoForge 升级 primer] 资源定位类为 `net.minecraft.resources.ResourceLocation`（改名发生在 1.21.11）。

## 已验证经验

- [vs-1.21.11-mojmap.md](vs-1.21.11-mojmap.md)：迁移到 1.21.11 时逐条核对的 API 差异（`[实测]` 组织四构建实测，含各条目适用边界）。
- 配套实测的多版本 × 多 Loader 项目结构见 [../loaders/fabric-vs-neoforge-1.21.x.md](../../loaders/fabric-vs-neoforge-1.21.x.md)。
