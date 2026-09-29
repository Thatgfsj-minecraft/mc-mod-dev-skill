# Minecraft 1.7.10

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**（`[公开文档：…]` 条目以注明来源为准）；API 差异动手前仍须按 api-verification.md 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 7（运行要求）；构建普遍用 8（现代 JDK 跑不动该年代工具链） |
| 发布线 | 1.7.x 线末代版本（传奇级模组版本，modpack 时代起点） |
| Loader | Forge 10.13.x（事实唯一主流）；LiteLoader 作为轻量补充存在；NeoForge / Fabric / Quilt 均不支持（社区 Legacy Fabric 分叉非主流 `[未核实]`） |
| 映射 | MCP / SRG（`func_…` / `field_…` 名；无 Mojmap / Yarn） |
| 构建系统 | ForgeGradle 2.x + 老 Gradle + JDK 8 组合（组合约束见 [pitfalls A5](../../../pitfalls.md)） |

## 时代特征（影响实现的公开常识）

- [常识] 资源体系为 1.8 前老制：无 blockstate / 模型 JSON，方块变体走 metadata，材质走旧贴图注册。
- [常识] 类名 / 方法名是 MCP / SRG 体系，与现代 Mojmap / Yarn 知识不可类比；查名用 Linkie 的 MCP 命名空间。
- [常识] 注册、网络、渲染、存档 API 均为前现代体系——现代版本的写法**一律不可迁移**，以反编译与对应年代官方文档为准。
- [常识] 物品数据为裸 NBT 时代；Java 8 语法上限。

## 已验证经验

- 暂无。按 [versions/README](../README.md) 的"新增版本"流程沉淀。
