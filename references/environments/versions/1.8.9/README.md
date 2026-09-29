# Minecraft 1.8.9

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**（`[公开文档：…]` 条目以注明来源为准）；API 差异动手前仍须按 api-verification.md 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 8 |
| 发布线 | 1.8.x（Bountiful Update；1.8.9 为 PvP 模组常用目标） |
| Loader | Forge 11.15.x；现代 Loader（NeoForge / Fabric / Quilt）不支持 |
| 映射 | MCP / SRG |
| 构建系统 | ForgeGradle 2.x + 老 Gradle + JDK 8 组合（组合约束见 [pitfalls A5](../../../pitfalls.md)） |

## 时代特征（影响实现的公开常识）

- [常识] 1.8 引入 blockstate / 模型 JSON 资源体系（本版本已具备），但部分方块变体仍依赖 metadata。
- [常识] 类名 / 方法名为 MCP / SRG 体系，与现代映射知识不可类比。
- [常识] 注册 / 网络 API 为 Forge 旧体系；物品数据裸 NBT；Java 8 语法上限。

## 已验证经验

- 暂无。按 [versions/README](../README.md) 的"新增版本"流程沉淀。
