# Minecraft 1.21.10

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**（`[公开文档：…]` 条目以注明来源为准）；API 差异动手前仍须按 api-verification.md 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 21 |
| 发布线 | 1.21.x（1.21.9 热修，2025-10-07） |
| Loader | NeoForge 21.10.x（版本号镜像 MC 线，以 maven.neoforged.net 实际为准）；Forge（版本线 `[未核实]`，以 files.minecraftforge.net 为准）；Fabric；Quilt（跟进情况 `[未核实]`） |
| 映射 | Mojmap、Yarn、Parchment 均可用 |
| 构建系统 | ModDevGradle（NeoForge 侧）；Fabric Loom（Fabric / Quilt 侧，1.21.x 目标钉 1.17.21——Loom 1.18+ 要求 JVM 25，见 [pitfalls A1](../../../pitfalls.md)） |

## 时代特征（影响实现的公开常识）

- 与 1.21.9 同线（7 天热修），行为特征见 [1.21.9 卡](../1.21.9/README.md)：spawn chunk 移除（`[实测]`）、`SUCCESS_SERVER`（1.21.2+）、资源定位类仍为 `ResourceLocation`（`[公开文档：NeoForge primer]`）、数据组件模型。
- `[公开文档：NeoForge primer]` `ContainerUser` 抽象已引入（1.21.9 起）。

## 已验证经验

- 本版本夹在 [1.21.1 ↔ 1.21.11 已验证差异对](../1.21.1/vs-1.21.11-mojmap.md) 之间：从任一端迁移时先读该差异文件作候选清单，逐条核实适用边界（文件内有标注）。
