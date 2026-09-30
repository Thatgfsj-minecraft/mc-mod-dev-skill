# Minecraft 26.2

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**；API 差异动手前仍须按 api-verification.md 核实。**26.x 是日期式版本线 + 去混淆化时代**，与 1.21.x 的差异总览见 [1.21.11/vs-26.x-deobf.md](../1.21.11/vs-26.x-deobf.md)。

## 基本盘

| 项 | 值 |
|---|---|
| Java | **25**（硬性） |
| 发布线 | 26.x 日期式线（26.2，无混淆映射） |
| Loader | Fabric（Loom 1.18.2 新 id）；NeoForge `26.2.x` 线（**有正式版**，26.2.0.88） |
| 映射 | **无**（jar 即 mojang 名） |
| 构建系统 | Loom 1.18.2 + ModDevGradle 2.0.147（neoform `26.2-2`，MDG 无需升级） |
| Gradle | ≥9.7（实测 fabric 侧 9.7.1） |
| 已验证依赖坐标 | fabric-api `0.161.0+26.2`、fabric-loader 0.19.5、NeoForge `26.2.0.88`（[实测：2026-09-30 skyislands 四构建 + Fabric 服务器冒烟]） |

## 时代特征（影响实现的公开常识）

- [实测] 与 26.1 同 API 面：工具链断层见 [vs-26.x-deobf §1](../../1.21.11/vs-26.x-deobf.md)，SavedDataType id=Identifier、事件改名、`setRespawnData` 不变均成立。
- [实测] **noise_router 仍是 `preliminary_surface_level`**（`chunk_surface_level` 改名发生在 26.3）——26.1/26.2/1.21.11 同键，worldgen JSON 无需改。

## 已验证经验

- [实测：2026-09-30 skyislands 移植（四构建编译 + Fabric 服务器两轮冒烟）] 主岛四探针（草/基岩/树/箱子）+ 负对照、下界虚空、forceload 跨重启持久（"Loading 25 persistent chunks"）、SavedData 防重建跨重启、level.dat spawn 复合结构实证。全程服务端零异常。
- [实测·测试] mineflayer 4.39.0 **不支持 26.2**（auto-detect ping 到版本号但 minecraft-data 无协议定义，"No data available for version 26.2"）——bot 测试跳过，RCON 断言不受影响。
- [实测·NeoForge] 26.2 线正式版正常（对比：21.9 线与 26.3 线只有 beta）——"每条线独立核实正式/beta"规程（pitfalls A6）持续成立。
