# Minecraft 1.18.2

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**（`[公开文档：…]` 条目以注明来源为准）；API 差异动手前仍须按 api-verification.md 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 17 |
| 发布线 | 1.18.x（Caves & Cliffs Part II；1.17 起进入 Java 16/17 + 官方映射时代） |
| Loader | Forge 40.x（公开版本线，非本组织验证）；Fabric；Quilt；无 NeoForge（1.20.1 才分叉） |
| 映射 | Mojmap（official，ForgeGradle 主流配置）、Yarn、Parchment 均可用 |
| 构建系统 | ForgeGradle 5.x（Forge 侧）；Fabric Loom（Fabric / Quilt 侧） |
| 已验证依赖坐标 | [实测] `net.fabricmc:fabric-loader:0.19.5`（meta.fabricmc.net 对 1.18.2 最新稳定）、`net.fabricmc.fabric-api:fabric-api:0.77.0+1.18.2`（maven-metadata 尾部最新）均正常解析；Loom `1.17.21` + Gradle 9.5.1 + JDK21 跑 `options.release = 17` 编译通过 |

## 时代特征（影响实现的公开常识）

- [常识] Flattening 后现代资源体系；blockstate / 模型 JSON。
- [常识] 物品数据裸 NBT（数据组件 1.20.5 才引入）。
- [常识] `InteractionResult` 旧模型（`SUCCESS_SERVER` 1.21.2+ 才拆分）；Mojmap 语境下资源定位类为 `ResourceLocation`。
- [实测] `new ResourceLocation(ns, path)` 公有构造在 1.18.2 在；`ResourceLocation.fromNamespaceAndPath` 工厂**不在**（1.20.5 才引入）。
- [实测] `InteractionResult.SUCCESS` 静态字段在；`SUCCESS_SERVER` **不在**（1.21.2 才拆分）。
- [实测] `DimensionType::bedWorks` 实例方法可作 `Predicate<DimensionType>` 方法引用。
- [实测] `net.minecraft.world.entity.ContainerUser` **不存在**（1.21.9+ 才有）。

## 已验证经验

2026-09-30 实测轮（Fabric + Mojmap，工程 `O:\clawwork\chuansongmen\skill-practice\1.18.2-fabric\`，拷自 1.20.1 模板仅改坐标）：

- [实测] 成立 ×3：`new ResourceLocation("a","b")`、`InteractionResult.SUCCESS` 静态引用、`Predicate<DimensionType> p = DimensionType::bedWorks;` —— 同一编译中零报错，随后删失败探针后 `BUILD SUCCESSFUL in 44s`（增量）。
- [实测] 证伪 ×3（javac 报错原文，冷缓存首编译 3m27s 内得出）：
  - `ResourceLocation.fromNamespaceAndPath("a","b")` → `错误: 找不到符号 / 符号: 方法 fromNamespaceAndPath(String,String) / 位置: 类 ResourceLocation`
  - `InteractionResult.SUCCESS_SERVER` → `错误: 找不到符号 / 符号: 变量 SUCCESS_SERVER / 位置: 类 InteractionResult`
  - `import net.minecraft.world.entity.ContainerUser;` → `错误: 找不到符号 / 符号: 类 ContainerUser / 位置: 程序包 net.minecraft.world.entity`（其使用处另报一条级联错，同根因）
- [实测] 工具链：JDK 21 + Gradle 9.5.1（wrapper 拷自 1.20.1 工程）+ Loom 1.17.21 + `options.release = 17` 一次通过，无需额外调整；`sourceCompatibility/targetCompatibility = 17` 写法照搬即可。
- [实测] 耗时：冷缓存首编译（含下载 1.18.2 jar + fabric-api 0.77.0+1.18.2 全模块）3m27s；之后增量编译 44s。
- 坑：fabric-loader 对 1.18.2 的"最新稳定"是 0.19.5（与 1.20.1 同线，直接同版本号可用）；fabric-api 1.18.2 线尾号为 `0.77.0+1.18.2`。
