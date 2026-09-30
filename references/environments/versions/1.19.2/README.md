# Minecraft 1.19.2

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**（`[公开文档：…]` 条目以注明来源为准）；API 差异动手前仍须按 api-verification.md 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 17 |
| 发布线 | 1.19.x（The Wild Update；1.19.2 为本线主流模组目标） |
| Loader | Forge 43.x（公开版本线，非本组织验证）；Fabric；Quilt；无 NeoForge（1.20.1 才分叉） |
| 映射 | Mojmap（official 主流）、Yarn、Parchment 均可用 |
| 构建系统 | ForgeGradle 5.x（Forge 侧）；Fabric Loom（Fabric / Quilt 侧）。[实测] JDK21 + Loom 1.17.21 + Gradle 9.5.1 + `options.release=17` 可构建 1.19.2 目标（Mojmap，`compileJava` 通过） |
| 已验证依赖坐标 | [实测] Fabric：`net.fabricmc:fabric-loader:0.19.5`（meta.fabricmc.net 查得最新稳定版，编译通过）；fabric-api 最新为 `0.77.0+1.19.2`（maven.fabricmc.net metadata 查得，本轮未编译依赖） |

## 时代特征（影响实现的公开常识）

- [常识] Flattening 后现代资源体系；blockstate / 模型 JSON。
- [常识] 物品数据裸 NBT（数据组件 1.20.5 才引入）。
- [常识] `InteractionResult` 旧模型（`SUCCESS_SERVER` 1.21.2+ 才拆分）；Mojmap 语境下资源定位类为 `ResourceLocation`。
- [实测] 资源定位：构造器时代——`new ResourceLocation("a", "b")` 公共构造器可用；静态工厂 `ResourceLocation.fromNamespaceAndPath(...)` **不存在**（1.20.5+ 才引入），编译报 `错误: 找不到符号`。
- [实测] 交互结果：`InteractionResult.SUCCESS` 静态字段存在；`InteractionResult.SUCCESS_SERVER` **不存在**（1.21.2 才拆分），编译报 `错误: 找不到符号`。
- [实测] `DimensionType::bedWorks` 为实例方法：可作 `Predicate<DimensionType>` 方法引用编译通过。
- [实测] `net.minecraft.world.entity.ContainerUser` **不存在**（1.21.9+ 才引入），import 编译报 `错误: 找不到符号`。
- [实测] `net.minecraft.resources.Identifier` **不存在**（1.21.11+ 才更名），import 编译报 `错误: 找不到符号`。

## 已验证经验

- [实测] Fabric + Mojmap 探针工程：`O:\clawwork\chuansongmen\skill-practice\1.19.2-fabric\`（由 1.20.1-fabric 拷贝改坐标，rootProject.name=`probe192`）。
- [实测] 工具链：Windows + Git Bash + JDK 21，Loom 1.17.21 + Gradle 9.5.1（wrapper 复用），`build.gradle` 加 `options.release = 17` 即可编译 1.19.2（目标 Java 17），`GRADLE_USER_HOME=O:\clawwork\chuansongmen\.gradle-home`。
- [实测] 耗时：jar/映射缓存冷启动下首编译（`compileJava`）约 83 秒（含 Minecraft/映射/loader 下载）；缓存热后约 20–24 秒。
- [实测] 探针结论（2026-09-30）：探针一 3 项全过（构造器 / `SUCCESS` / `bedWorks`），探针二 4 项全败且均为 `错误: 找不到符号`（`fromNamespaceAndPath` / `SUCCESS_SERVER` / `ContainerUser` / `Identifier`），与 1.20.1 同模式，且 1.19.2 处于更早的构造器时代端点。
