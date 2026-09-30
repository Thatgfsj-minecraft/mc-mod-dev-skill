# Minecraft 1.21.4

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**（`[公开文档：…]` 条目以注明来源为准）；API 差异动手前仍须按 api-verification.md 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 21 |
| 发布线 | 1.21.x（1.21.2 / 1.21.3 / 1.21.4） |
| Loader | NeoForge 21.4.x；Forge 54.x（公开版本线，非本组织验证）；Fabric；Quilt（跟进情况 `[未核实]`） |
| 映射 | Mojmap、Yarn、Parchment 均可用 |
| 构建系统 | ModDevGradle（NeoForge 侧）；Fabric Loom（Fabric / Quilt 侧，1.21.x 目标钉 1.17.21——Loom 1.18+ 要求 JVM 25，见 [pitfalls A1](../../../pitfalls.md)） |
| 已验证依赖坐标 | `[实测]` Fabric：`com.mojang:minecraft:1.21.4` + `net.fabricmc:fabric-loader:0.19.5`（当时 meta 最新稳定版）+ Loom `1.17.21` + JDK 21，compileJava 通过（探针工程 skill-practice/1.21.4-fabric） |

## 时代特征（影响实现的公开常识）

- [常识] 数据组件模型：物品自定义存储用组件。
- [实测] `InteractionResult` 新模型：`SUCCESS_SERVER` 已拆分（1.21.2+），纯服务端动作用 `SUCCESS_SERVER`——用错编译能过但行为错（1.21.4 上 `InteractionResult.SUCCESS_SERVER` 静态字段引用编译通过，见已验证经验）。
- [实测] 资源定位类仍为 `net.minecraft.resources.ResourceLocation`（改名发生在 1.21.11；1.21.4 上 `ResourceLocation.fromNamespaceAndPath` 编译通过、`net.minecraft.resources.Identifier` import 报"找不到符号"，见已验证经验）。

## 已验证经验

- 本版本夹在 [1.21.1 ↔ 1.21.11 已验证差异对](../1.21.1/vs-1.21.11-mojmap.md) 之间：从任一端迁移时先读该差异文件作候选清单，逐条核实适用边界（文件内有标注）。
- [实测] 探针验证（2026-09-30，工程 `skill-practice/1.21.4-fabric`，由 1.21.9-fabric 拷贝改造；工具链 Fabric Loom 1.17.21 + Gradle wrapper + JDK 21，Mojmap，`GRADLE_USER_HOME` 缓存全热；探针一热编译秒级通过，与前两轮 ~4s 同级，整轮验证含沉淀 < 10 分钟，未启动游戏、未 genSources）：
  - 成立：`ResourceLocation.fromNamespaceAndPath(ns, path)` 编译通过——仍为 `ResourceLocation`，与 1.21.8 同模式。
  - 成立：`InteractionResult.SUCCESS_SERVER` 静态字段引用编译通过——1.21.2+ 拆分在本版本成立。
  - 成立：`Predicate<DimensionType> p = DimensionType::bedWorks;` 编译通过——`bedWorks()` 在 1.21.4 仍是**实例方法**（`DimensionType` → boolean），方法引用即证明；与 1.21.8 / 1.21.9 同模式，静态调用会编译失败。
  - 证伪：`import net.minecraft.world.entity.ContainerUser;` → `错误: 找不到符号 / 符号: 类 ContainerUser / 位置: 程序包 net.minecraft.world.entity`——该类 1.21.4 不存在，与 1.21.8 一致（1.21.9 才引入）。
  - 证伪：`import net.minecraft.resources.Identifier;` → `错误: 找不到符号 / 符号: 类 Identifier / 位置: 程序包 net.minecraft.resources`——1.21.4 无 `Identifier`，改名发生在 1.21.11。
  - 结论：1.21.4 在本轮全部探针上与 1.21.8 **同模式**；差异对清单中 1.21.2–1.21.8 段的结论可直接外推到 1.21.4。
