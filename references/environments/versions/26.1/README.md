# Minecraft 26.1

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**；API 差异动手前仍须按 api-verification.md 核实。**26.x 是日期式版本线 + 去混淆化时代**，与 1.21.x 的差异总览见 [1.21.11/vs-26.x-deobf.md](../1.21.11/vs-26.x-deobf.md)。

## 基本盘

| 项 | 值 |
|---|---|
| Java | **25**（硬性，piston-meta majorVersion=25） |
| 发布线 | 26.x 日期式线（26.1 / 26.1.1 / 26.1.2，无混淆映射） |
| Loader | Fabric（Loom **1.18.2**，插件 id `net.fabricmc.fabric-loom`）；NeoForge `26.1.x` 线（**26.1.0.x / 26.1.1.x 全 beta，正式版自 26.1.2.71 起**） |
| 映射 | **无**（jar 即 mojang 名，无 mojmap/intermediary/remap） |
| 构建系统 | Loom 1.18.2（新 id，无 mappings 行，`implementation`）；ModDevGradle 2.0.147（neoform `26.1.2-1`） |
| Gradle | ≥9.7（Loom 1.18.2 硬校验 plugin-api 9.7.0；实测 9.8.0 跑 JVM 25 正常） |
| 已验证依赖坐标 | fabric-api `0.155.3+26.1.2`、fabric-loader 0.19.5、NeoForge `26.1.2.112`（[实测：2026-09-30 skyislands 四构建 + Fabric 服务器三启冒烟 + bot E2E 全绿]） |

## 时代特征（影响实现的公开常识）

- [实测] 工具链断层与核心 API 变更：见 [vs-26.x-deobf §1/§2](../../1.21.11/vs-26.x-deobf.md)（SavedDataType 首参 Identifier、事件改名 ServerEntityLevelChangeEvents）。
- [实测] noise_router / world_preset 数据包格式与 1.21.11 一致（`preliminary_surface_level` 仍有效）。

## 已验证经验

- [实测：2026-09-30 skyislands 移植（四构建编译 + Fabric 专用服务器三次启动冒烟 + mineflayer bot E2E）] 主岛三探针（草/基岩锚/树 6 64 10）+ 负对照、下界虚空（chunk 0,0 邻域，遵守 D7）、forceload 跨重启持久、**首进下界一次性萤石平台 bot 全测通过**（mineflayer 4.39.0 支持协议 775）。三份 console 日志零 ERROR/Exception。证据：`sky-islands-test/server-d-261` 冒烟记录 + mapped jar javap/unzip。
- [实测·工具链] foojay-resolver 可自动进 JDK 25 到 `.gradle-home/jdks/`；换 JAVA_HOME 后复用旧 daemon 报 "supplied javaHome invalid"（元数据残留），先 `--stop`。
