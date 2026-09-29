# Minecraft 1.12.2

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**（`[公开文档：…]` 条目以注明来源为准）；API 差异动手前仍须按 api-verification.md 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 8 |
| 发布线 | 1.12.x（World of Color Update；1.7.10 之后存量 mod 最大的长线版本之一） |
| Loader | Forge 14.23.x（14.23.5.x 为事实标准分支）；LiteLoader 存在；NeoForge / Fabric / Quilt 不支持（Legacy Fabric 非主流 `[未核实]`） |
| 映射 | MCP（stable_39 为本线终版稳定映射） |
| 构建系统 | ForgeGradle 2.x（官方 MDK；社区有后期工具链移植 `[未核实]`）+ JDK 8 + 老 Gradle（组合约束见 [pitfalls A5](../../../pitfalls.md)） |

## 时代特征（影响实现的公开常识）

- [常识] metadata 副方块仍在（Flattening 发生在 1.13）。
- [常识] 注册 / 事件为 Forge 旧 API 体系，与 NeoForge 现代写法不同代。
- [常识] 物品数据裸 NBT；类名 / 方法名为 MCP / SRG 体系；Java 8 语法上限。
- [常识] 现代版本的任何写法（注册、网络、渲染、数据存储）都不可迁移到本版本，一律以反编译与对应年代官方文档为准。

## 已验证经验

- 暂无。按 [versions/README](../README.md) 的"新增版本"流程沉淀。
