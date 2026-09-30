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
| 已验证依赖坐标 | fabric-api `0.138.4+1.21.10`、fabric-loader 0.19.3（[实测：2026-09-30 skyislands 1.21.10-fabric 全量构建+服务器冒烟]）；NeoForge `21.10.64`（[实测：ModDevGradle 2.0.147 全量构建]） |

## 时代特征（影响实现的公开常识）

- 与 1.21.9 同线（7 天热修），行为特征见 [1.21.9 卡](../1.21.9/README.md)：spawn chunk 移除（`[实测]`）、`SUCCESS_SERVER`（1.21.2+）、资源定位类仍为 `ResourceLocation`（`[公开文档：NeoForge primer]`）、数据组件模型。
- `[公开文档：NeoForge primer]` `ContainerUser` 抽象已引入（1.21.9 起）。

## 已验证经验

- 本版本夹在 [1.21.1 ↔ 1.21.11 已验证差异对](../1.21.1/vs-1.21.11-mojmap.md) 之间：从任一端迁移时先读该差异文件作候选清单，逐条核实适用边界（文件内有标注）。
- [实测：2026-09-30 skyislands 移植（四构建编译 + Fabric 专用服务器冒烟 + mineflayer bot E2E）] 与 1.21.9 完全同 API 面，同一份源码零分支：`ResourceLocation`（无 `Identifier`）、`SavedDataType` codec 式存储、`MinecraftServer.setRespawnData(LevelData.RespawnData.of(...))`、noise_router 字段 `preliminary_surface_level` 全部成立；boot 冒烟（建岛三探针 + 下界虚空）与"首进下界一次性平台"行为测试全绿。mineflayer 4.39.0 可直连（1.21.10 与 1.21.9 共享协议 773）。证据：mapped jar javap/unzip + `sky-islands-test/server-c-12110` 冒烟记录。
