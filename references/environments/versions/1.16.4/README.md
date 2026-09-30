# Minecraft 1.16.4

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**（`[公开文档：…]` 条目以注明来源为准）；API 差异动手前仍须按 api-verification.md 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 8（1.17 才提升）；`options.release = 8` 在 JDK 21 javac 下实测编译通过（仅"源/目标值 8 已过时"警告）[实测：2026-09-30] |
| 发布线 | 1.16.x（Nether Update；与 1.16.5 差异极小，见 [1.16.5 卡](../1.16.5/README.md)） |
| Loader | Forge 35.x（公开版本线，非本组织验证）；Fabric / Quilt 可用；无 NeoForge（1.20.1 才分叉） |
| 映射 | MCP（snapshot / stable）为主流；Mojang 官方映射经 Loom `officialMojangMappings()` 在 1.16.4 上实测可用：layered 映射生成成功并以其编译出结果 [实测：2026-09-30，Loom 1.17.21] |
| 构建系统 | ForgeGradle 4.x（Forge 侧）；Fabric Loom（Fabric / Quilt 侧）[实测：Loom 1.17.21 全程驱动 1.16.4 无兼容报错] |
| 已验证依赖坐标 [实测：2026-09-30，坐标经 Loom 依赖解析 + javac 编译验证] | fabric-loader `0.19.5`（meta 最新稳定版）；fabric-api 坐标为 **`net.fabricmc.fabric-api:fabric-api`**，1.16 线版本后缀是 `+1.16`（不是 `+1.16.4`），最新 `0.42.0+1.16` 已随构建解析成功。**group 陷阱**：写成 `net.fabricmc:fabric-api`（少一层）会在 Gradle 解析报 `Could not find`（POM 直连亦 404），必须用 `net.fabricmc.fabric-api` 作 group |

## 构建系统 [实测：2026-09-30 夜间第 2 轮]

- [实测] 工具链：Windows + Git Bash + JDK 21，Gradle wrapper 9.5.1，Loom `1.17.21`（与 1.20.1 模板同版），`options.release = 8` 编译通过。工程：`O:\clawwork\chuansongmen\skill-practice\1.16.4-fabric\`（GRADLE_USER_HOME=`O:\clawwork\chuansongmen\.gradle-home`）。
- [实测] Loom 1.17.21 对 1.16.4 全流程无兼容报错：配置阶段通过 → 冷缓存下载并合并 `minecraft-client.jar` + `minecraft-server.jar` → `caches\fabric-loom\1.16.4\minecraft-merged.jar` → intermediary `1.16.4`（`intermediary-v2.tiny`）+ 官方 Mojmap layered 映射生成（`loom.mappings.1_16_4.layered+hash.2198-v2\mappings.tiny`）→ 合并 jar 重映射 → javac 正常出结果。
- [实测] 冷管线耗时与坑：首跑 11m45s，MC jar 下载+合并完成后死于 **maven.fabricmc.net 读超时**（拉 `intermediary-1.16.4.pom` 时 `Read timed out`，属官方 maven 偶发抽风，非兼容性问题）；利用已缓存 MC jar 重跑 4m45s 推进到依赖解析（当时 fabric-api group 写错），修正后再跑 **5m17s 完成 javac 出结论**。结论：1.16 线冷管线按 ≥20 分钟预算，且要给 fabric maven 超时留一次重试。另：前次构建被中断后曾提示 `Previous process has disowned the lock ... rebuilding loom cache`，Loom 自动重建映射缓存，不影响最终结果。

## 时代特征（影响实现的公开常识）

- [常识] Flattening 已完成（1.13+）：无 metadata 副方块，blockstate 体系。
- [常识] 物品数据裸 NBT（数据组件 1.20.5 才引入）。
- [实测] `InteractionResult` 旧模型成立：`InteractionResult.SUCCESS` 直接常量编译通过（**不是** `InteractionResult.Result.SUCCESS` 嵌套枚举）；`SUCCESS_SERVER` 不存在（javac：`找不到符号: 变量 SUCCESS_SERVER`），反向证实"1.21.2+ 才拆分"。Mojmap 语境下资源定位类为 `ResourceLocation`（`net.minecraft.resources.Identifier` 报 `找不到符号: 类 Identifier`，即 Yarn 名不存在）。
- [实测] 资源定位为**构造器时代**：`new ResourceLocation("a","b")` 编译通过；静态工厂 `ResourceLocation.fromNamespaceAndPath("a","b")` 不存在（javac：`找不到符号: 方法 fromNamespaceAndPath(String,String)`）。
- [实测] Java 8 语法上限：`options.release = 8` 在 JDK 21 javac 下编译通过。
- [实测] fabric-api 版本后缀规律：1.16.x 线用 `+1.16`（不是 `+1.16.4`），选版本时 grep `+1.16` 收尾（maven-metadata.xml 核对 + 构建解析双确认）。

## 已验证经验

- [实测 2026-09-30 夜间第 2 轮] 探针一（预期成立）**3/3 全部成立**：`new ResourceLocation("a","b")` 双参构造器、`InteractionResult.SUCCESS` 直接常量、`DimensionType::bedWorks` 方法引用在同一次 javac 中零报错。证据文件 `src/main/java/probe/Probe1.java`，工程 `O:\clawwork\chuansongmen\skill-practice\1.16.4-fabric\`。
- [实测 2026-09-30 夜间第 2 轮] 探针二（预期全败）**4/4 失败**，报错原文（javac，中文区域设置）：`ContainerUser` → `错误: 找不到符号 符号: 类 ContainerUser 位置: 程序包 net.minecraft.world`；`Identifier` → `错误: 找不到符号 符号: 类 Identifier 位置: 程序包 net.minecraft.resources`；`InteractionResult.SUCCESS_SERVER` → `错误: 找不到符号 符号: 变量 SUCCESS_SERVER 位置: 类 InteractionResult`；`ResourceLocation.fromNamespaceAndPath("a","b")` → `错误: 找不到符号 符号: 方法 fromNamespaceAndPath(String,String) 位置: 类 ResourceLocation`。证据文件 `src/main/java/probe/Probe2.java`。
- [实测 2026-09-30 夜间第 2 轮] 依赖坐标实践：`net.fabricmc.fabric-api:fabric-api:0.42.0+1.16` 与 `net.fabricmc:fabric-loader:0.19.5` 均进入 Loom 解析并参与编译；错写 group `net.fabricmc:fabric-api` 会得到 `Could not find net.fabricmc:fabric-api:0.42.0+1.16`（Gradle 列出的 maven.fabricmc.net 路径 404）。
- 与 1.16.5 卡互证：两版本同处"构造器时代 + 旧交互模型"，1.16.4 的探针结论可直接类比 1.16.5（反之亦然），差异仅在补丁号。
