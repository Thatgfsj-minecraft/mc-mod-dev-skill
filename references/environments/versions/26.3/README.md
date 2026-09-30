# Minecraft 26.3

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**；API 差异动手前仍须按 api-verification.md 核实。**26.x 是日期式版本线 + 去混淆化时代**，与 1.21.x 的差异总览见 [1.21.11/vs-26.x-deobf.md](../1.21.11/vs-26.x-deobf.md)。

## 基本盘

| 项 | 值 |
|---|---|
| Java | **25**（硬性） |
| 发布线 | 26.x 日期式线（当前最新正式；26.4 只有快照），无混淆映射 |
| Loader | Fabric（Loom 1.18.2 新 id）；NeoForge `26.3.x` 线（**只有 beta**，至 26.3.0.37-beta，按 pitfalls A6 钉 beta 并注明） |
| 映射 | **无**（jar 即 mojang 名，启动日志 `Mappings not present!`） |
| 构建系统 | Loom 1.18.2 + ModDevGradle 2.0.147 |
| Gradle | ≥9.7（实测 9.7.0 跑 JVM 25 正常） |
| 已验证依赖坐标 | fabric-api `0.161.0+26.3`（0.161.1/2 是 +26.4）、fabric-loader 0.19.5、NeoForge `26.3.0.37-beta`（[实测：2026-09-30 skyislands 四构建 + Fabric 服务器两轮冒烟]） |

## 时代特征（影响实现的公开常识）

- [实测] **worldgen 数据包大改（重写级，26.3 独有）**：`preliminary_surface_level`→**`chunk_surface_level`**、NoiseRouter 15→8 参、`surface_rule`→**`material_rule`**、`aquifers_enabled`→**`aquifers`**（Optional 对象缺省关）、`ore_veins_enabled` 删除、`NoiseSettings` 缩为 `(minY,height)`（size_horizontal/size_vertical 删除）、**BlockState JSON 改纯字符串**或 `{id, properties}`（不再收 `{"Name":...}`，报错判别词 `No key id in MapLike`）——详见 [vs-26.x-deobf §3](../1.21.11/vs-26.x-deobf.md)。
- [实测] `DimensionDataStorage` 改名 **`SavedDataStorage`**（26.3 起）；SavedDataType 首参 Identifier、Fabric 事件改名同 26.1/26.2。

## 已验证经验

- [实测：2026-09-30 skyislands 移植（四构建编译 + Fabric 服务器两轮冒烟，字节码 major 69=Java25 验证）] 主岛五探针（草/泥土/基岩锚/树 6 64 10/箱子 10 64 8）+ 负对照、下界虚空（近原点，D7 规避有效）、forceload 跨重启持久 + SavedData 防重建跨重启（两轮全 PASS，最慢 world 保存约 3 分钟正常完成）。
- [实测·测试] mineflayer 4.39.0 **不支持 26.3**（protocolVersions 认识 777 但协议定义数据缺失，createBot 直接抛 unsupported）——bot 跳过，RCON 断言不受影响。
- [实测·工具链] MDG 2.0.147 在**本机无可探测 JDK 25** 时走供应商下载路径引用 `JvmVendorSpec.IBM_SEMERU`（Gradle 9 已删该枚举）→ 配置期崩；先装 JDK 25 并使其可被探测（JAVA_HOME/PATH）即不触发。
