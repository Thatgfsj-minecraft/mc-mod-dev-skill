# Minecraft 1.16.5

> 可信级别声明：本卡除带 `[实测：…]` 标签的条目外均为**结构事实 / 公开常识**（`[公开文档：…]` 条目以注明来源为准）；API 差异动手前仍须按 api-verification.md 核实。

## 基本盘

| 项 | 值 |
|---|---|
| Java | 8（1.17 才提升）；fabric-meta 对 1.16.5 loader 报 `min_java_version: 8` [实测：2026-09-30 meta.fabricmc.net/v2/versions/loader/1.16.5] |
| 发布线 | 1.16.x（Nether Update；1.16.5 = 1.16.4 + 安全修复，是本线事实上的主流模组目标） |
| Loader | Forge 36.x（公开版本线，非本组织验证；36.2.x 为常见分支）；Fabric / Quilt 可用；无 NeoForge（1.20.1 才分叉） |
| 映射 | MCP（snapshot / stable）为当年主流；Mojang 官方映射可用 [实测：2026-09-30 Loom 1.17.21 `officialMojangMappings()` 下探针按 Mojmap 类名编译通过] |
| 构建系统 | ForgeGradle 4.x（Forge 侧）；Fabric Loom（Fabric / Quilt 侧） |
| 已验证依赖坐标 [实测：坐标来自官方源查询；loader 已进入 Loom 依赖解析，fabric-api 仅元数据核对未编译解析] | fabric-loader `0.19.5`（meta 最新稳定版，1.16.5 与 1.20.1 当前同为 0.19.5）；fabric-api 对 1.16.x 后缀为 `+1.16`（非 `+1.16.5`），最新 `0.42.0+1.16`（maven.fabricmc.net maven-metadata.xml） |

## 构建系统 [实测：2026-09-30 编译验证完成]

- [实测] 工具链：Windows + Git Bash + JDK 21，Gradle wrapper 9.5.1，Loom `1.17.21`（与 1.20.1 模板同版），`options.release = 8` **实测可用**：javac 仅报 3 条「源值/目标值 8 已过时」弃用警告，编译成功。工程：`O:\clawwork\chuansongmen\skill-practice\1.16.5-fabric\`（GRADLE_USER_HOME=`O:\clawwork\chuansongmen\.gradle-home`）。
- [实测] Loom 1.17.21 对 1.16.5 **无插件级兼容报错**：配置阶段通过，冷缓存下成功下载并合并 `minecraft-client.jar` + `minecraft-server.jar` → `caches\fabric-loom\1.16.5\minecraft-merged.jar`。冷缓存首轮 `compileJava` 约 19 分钟未达 javac（瓶颈是下载/合并/重映射，非兼容性）；**合并 jar 缓存已热后复跑：`BUILD SUCCESSFUL in 4m`（Gradle 自报，含 daemon 启动）**。结论：Loom 现代版可完整驱动 1.16.5 编译；首轮必须给足下载时间（建议 ≥20 分钟或先预热缓存），热缓存下数分钟内到达 javac。
- [实测] API 探针一通过：Probe1（`new ResourceLocation("a","b")` / `InteractionResult.SUCCESS` / `DimensionType::bedWorks`，Mojmap）编译成功并产出 `Probe1.class`，无需回退到 `InteractionResult.Result.SUCCESS` 形态。

## 时代特征（影响实现的公开常识）

- [常识] Flattening 已完成（1.13+）：无 metadata 副方块，blockstate 体系。
- [常识] 物品数据裸 NBT（数据组件 1.20.5 才引入）。
- [实测] `InteractionResult` 旧模型：`InteractionResult.SUCCESS` **直接静态字段成立**（无需 `InteractionResult.Result.SUCCESS` 嵌套枚举形态）；`SUCCESS_SERVER` **不存在**（编译报「错误: 找不到符号——变量 SUCCESS_SERVER，位置: 类 InteractionResult」，与其 1.21.2+ 才拆分的常识一致）。Mojmap 语境下资源定位类为 `ResourceLocation`。
- [实测] `ResourceLocation`：`new ResourceLocation(String, String)` 双参构造器**可用**；`ResourceLocation.fromNamespaceAndPath(String,String)` 静态工厂**不存在**（编译报「错误: 找不到符号——方法 fromNamespaceAndPath(String,String)，位置: 类 ResourceLocation」；该形态属 1.21+ 时代）。
- [实测] `net.minecraft.world.entity.ContainerUser` **不存在**（编译报「错误: 找不到符号——类 ContainerUser，位置: 程序包 net.minecraft.world.entity」；1.21.2+ 才引入）。
- [实测] `net.minecraft.resources.Identifier` **不存在**（编译报「错误: 找不到符号——类 Identifier，位置: 程序包 net.minecraft.resources」；1.16.5 Mojmap 中只有 `ResourceLocation`）。
- [常识] Java 8 语法上限。
- [实测] fabric-api 版本后缀规律：1.16.x 线用 `+1.16`（不是 `+1.16.5`），选版本时 grep `+1.16` 收尾。

## 已验证经验

- [实测 2026-09-30 夜间第 2 轮（合并 jar 缓存已热）] 编译验证完成，总耗时约 6 分钟（硬上限 12 分钟内）：探针一 `compileJava` → `BUILD SUCCESSFUL in 4m`（含 Gradle daemon 启动；证实 19 分钟冷缓存卡点纯系下载/合并，热缓存 4 分钟内到达 javac）；探针二（`ResourceLocation.fromNamespaceAndPath` / `InteractionResult.SUCCESS_SERVER` / `import ...ContainerUser;` / `import ...resources.Identifier;` 四项同文件一次编译）→ `BUILD FAILED in 3s`，**4 项预期失败全部命中、一次收齐报错**，javac 原文均为「错误: 找不到符号」（逐条原文见「时代特征」各条）。`options.release=8` 实测与 Loom 1.17.21 + JDK 21 javac 相容。探针工程与 `Probe1.java`/`Probe2.java` 保留在 `O:\clawwork\chuansongmen\skill-practice\1.16.5-fabric\` 供复跑。未启动游戏/服务器、未 genSources。
- [实测 2026-09-30 夜间第 1 轮] 冷缓存首次验证：Loom 对 1.16.5 配置/合并 jar 阶段无兼容错误，但首编译在下载/映射/重映射阶段超出 18 分钟预算被终止；`minecraft-merged.jar` 已写入缓存（为第 2 轮 4 分钟完成铺路）。
