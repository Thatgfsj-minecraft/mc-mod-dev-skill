# Minecraft 1.20.1

> 可信级别声明：本卡除「已验证经验」外均为**结构事实 / 公开常识**；任何 API 差异动手前必须按 [api-verification](../../../api-verification.md) 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 17 |
| 发布线 | 1.20（Trails & Tales） |
| Loader | Forge 47.x；NeoForge 47.1.x（1.20.1 分叉点，与 Forge 基本同源）；Fabric；Quilt |
| 映射 | Mojmap、Yarn、Parchment 均可用 |
| 构建系统 | ForgeGradle（Forge / NeoForge 侧）；Fabric Loom（Fabric / Quilt 侧）。[实测] JDK21 + Loom 1.17.21 + Gradle 9.5.1 + `options.release = 17` 可构建 1.20.1 目标（JDK 21 的 javac 以 `--release 17` 编译通过；Loom 1.17 对旧 MC 版本无下界问题） |
| 已验证依赖坐标 | fabric-loader 0.19.5（[实测] meta.fabricmc.net/v2/versions/loader/1.20.1 最新稳定版）；fabric-api 0.92.12+1.20.1（[实测] maven.fabricmc.net maven-metadata，该线最新，仅记录坐标未引入依赖） |

## 时代特征（影响实现的公开常识）

- 物品自定义数据用**任意 NBT**（数据组件 1.20.5 才引入）；以 NBT 为存储的物品功能按 NBT 模型实现。
- [实测] `InteractionResult` 旧模型：静态字段 `SUCCESS` 存在，纯服务端动作用它；`SUCCESS_SERVER` **实测不存在**（编译报 `找不到符号: 变量 SUCCESS_SERVER`）——拆分边界 = 1.21.2 的下端已实证（上端 1.21.2+ 另有 primer 与 1.21.4/8/9 实测夹住）。
- [实测] 资源定位类为 `net.minecraft.resources.ResourceLocation`：类存在；**公共构造器时代**，`new ResourceLocation(ns, path)` 可用；静态工厂 `fromNamespaceAndPath` **实测不存在**（编译报 `找不到符号: 方法 fromNamespaceAndPath`，该工厂是 1.20.5 才引入）。改名 `Identifier` 发生在 1.21.11（见 NeoForge 升级 primer），本版本 `import net.minecraft.resources.Identifier` 实测报错。
- [实测] `DimensionType` 有实例方法 `bedWorks()`（`Predicate<DimensionType> p = DimensionType::bedWorks;` 编译通过）。
- [实测] `net.minecraft.world.entity.ContainerUser` 不存在（编译报 `找不到符号: 类 ContainerUser`，该接口 1.21.2+ 才引入）。

## 已验证经验

2026-09-30 [实测] Fabric + Mojmap 编译探针（Loom 1.17.21 / Gradle 9.5.1 / JDK21 `--release 17` / loader 0.19.5）：

- `new ResourceLocation("a", "b")` 编译通过；`ResourceLocation.fromNamespaceAndPath(...)` 编译失败。→ 1.20.1 写资源路径用公共构造器，别照抄 1.20.5+ 的静态工厂写法。
- `InteractionResult.SUCCESS` 编译通过；`InteractionResult.SUCCESS_SERVER` 编译失败。→ 1.20.1 纯服务端动作返回 `SUCCESS`；`SUCCESS_SERVER` 判据（≥1.21.2）在本版本下端成立。
- `DimensionType::bedWorks`（实例方法方法引用）编译通过。
- `net.minecraft.world.entity.ContainerUser`、`net.minecraft.resources.Identifier` 编译失败。→ 这两个符号是 1.21.2+ / 1.21.11+ 时代的判据，1.20.1 均不可用。
- 工具链：JDK 21 环境构建 1.20.1 需在 build.gradle 显式 `options.release = 17`（仅设 sourceCompatibility/targetCompatibility 不够保险）；首次构建需下载 1.20.1 jar 与 Mojmap 映射，冷缓存约 1.5 分钟。
