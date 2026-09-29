# Phase 0 — Project Discovery / Environment Discovery

Phase 0 的目标：在任何实现之前，从项目文件里**识别并记录**目标开发环境。本文是 `SKILL.md` §3 的完整执行手册。

## 0. 为什么必须先侦察

Minecraft Mod API 的跨版本 / 跨 Loader 差异巨大且点状分布：类名、方法签名、参数、注册方式、事件 API、网络 API、命令 API、`ResourceLocation`/`Identifier` 类 API、Server/Client 类型归属、数据组件机制都可能在版本间变化。凭通用记忆直接生成代码是本 Skill 明确禁止的行为（`SKILL.md` §2.1 / §7）。

## 1. 检查清单（按顺序执行，记录证据）

### 1.1 构建配置

| 文件 | 提取什么 |
|---|---|
| `gradle.properties` | `minecraft_version`、`neo_version`、`forge_version`、`loader_version`、loom / MDG 插件版本、mappings 相关键、模块开关 |
| `gradle/libs.versions.toml` | version catalog：minecraft、fabric-api、neoforge、forge、loom、moddev、parchment 等 |
| `build.gradle(.kts)` | 应用的插件 id；`dependencies` 里的 minecraft / loader / api 坐标；mappings 配置；javaToolchain；子项目配置 |
| `settings.gradle(.kts)` | `pluginManagement` 仓库（Fabric maven / NeoForged maven / Forge maven）；`include` 的子模块 |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 版本（与 JVM 要求联动） |

**构建系统识别（按插件 id）**：

| 插件 | 结论 |
|---|---|
| `fabric-loom` | Fabric Loom（Fabric / Quilt 项目主流） |
| `net.neoforged.moddev` | ModDevGradle（NeoForge 现代官方插件） |
| `net.neoforged.gradle.*` | NeoGradle（NeoForge 旧插件，已让位 ModDevGradle） |
| `net.minecraftforge.gradle` | ForgeGradle（Forge） |
| `architectury-plugin` / `dev.architectury.loom` | Architectury 体系（多为 common + platform 多 Loader） |
| `unimined.*` | Unimined（另一套多 Loader 构建插件） |
| `stonecutter`（settings 插件） | Stonecutter（多版本单代码库预处理器） |

**插件仓库也是信号**：`maven.fabricmc.net` → Fabric 系；`maven.neoforged.net` → NeoForge；Forge maven → Forge。

**权威兜底**：属性文件互相矛盾时，看依赖树的实际解析结果：

```bash
./gradlew -q :<module>:dependencies --configuration compileClasspath | grep -iE 'minecraft|fabric|neoforge|forge'
```

### 1.2 Loader 元数据

| 文件（`src/main/resources/` 或各模块 resources 下） | 结论与提取项 |
|---|---|
| `fabric.mod.json` | **Fabric**。`depends.minecraft` 给出目标版本范围；`depends.java` 给出 Java 要求；`entrypoints` 给出入口；`environment` 给出侧（`*` / `client` / `server`） |
| `quilt.mod.json` | **Quilt** |
| `META-INF/neoforge.mods.toml` | **NeoForge**（较新版本线的命名） |
| `META-INF/mods.toml` | **Forge**（或早期 NeoForge） |
| `mcmod.info` | 极老的 Forge 时代项目 |

同时存在多份 metadata → **多 Loader / 多版本项目**：按模块分别记录，找出各目标的 resources 目录与子项目。

### 1.3 映射体系

构建配置判据：

| 配置 | 结论 |
|---|---|
| `loom.officialMojangMappings()`；ForgeGradle `mappings channel: 'official'` | **Mojmap**（官方映射） |
| `mappings loom.layered { … "net.fabricmc:yarn:…" }` | **Yarn**（可叠加 Parchment） |
| ForgeGradle `mappings channel: 'snapshot_…' / 'stable_…'` | **MCP / SRG 系**（老 Forge） |
| `org.parchmentmc.data:parchment-…` 或 ModDevGradle `parchment {}` | 叠加 **Parchment** 参数名层 |

代码判据（看**完整包路径**，不只看类名）：

| 现有代码里出现 | 结论 |
|---|---|
| `net.minecraft.world.item.ItemStack`、`net.minecraft.resources.*` | Mojmap |
| `net.minecraft.item.ItemStack`、`net.minecraft.util.Identifier`（Yarn 包布局） | Yarn |
| `func_12345_a` / `m_12345_` 形式的方法名 | SRG 中间名（映射未完成或老工具链） |

识别出 MCP / SRG（老 Forge 时代项目）：先查 `environments/versions/` 是否有该版本的结构事实卡（1.7.10 / 1.8.9 / 1.12.2 等已有），没有再走 `SKILL.md` §13 兜底。查名用 Linkie 的 MCP / SRG 命名空间（见 `api-verification.md` §3）；构建工具链组合约束见 `pitfalls.md` A5。

注意：Mojmap 自身也会在版本间改名（已验证：1.21.11 线把 `ResourceLocation` 改名 `Identifier`，但包仍在 `net.minecraft.resources`）——所以包路径比类名可靠。见 `environments/versions/1.21.1/vs-1.21.11-mojmap.md`。

**版本卡交叉校验**：识别出版本号后，若 `environments/versions/<版本>/README.md` 存在，读它交叉校验 Java / Loader / 映射可用性；版本卡里未标注"已验证"的内容只是结构事实。

### 1.4 Java 版本

- 判据优先级：`build.gradle` 的 `java.toolchain` > `sourceCompatibility` / `targetCompatibility` > loader metadata 里的 java 依赖（如 `fabric.mod.json` 的 `depends.java`）> CI 配置。
- 常见对应（**仅作交叉校验，以项目配置为准，新版本可能再提高**）：

| Minecraft | 常见 Java |
|---|---|
| ≤ 1.16.x | 8 |
| 1.17.x | 16 |
| 1.18 – 1.20.4 | 17 |
| 1.20.5 – 1.21.x | 21 |
| 26.1+（2026 起的日期式版本线） | 25（更高；逐版本核对） |

### 1.5 项目架构

| 观察 | 结论 |
|---|---|
| 单 `src/main`，单份 metadata | single-loader 单项目 |
| `common/` + `fabric/` + `forge|neoforge/`（或类似）多模块 | common + platform 多 Loader |
| 仓库根下 `<版本>/<Loader>/` 多个完整独立 Gradle 项目 | 复制式多构建 |
| Stonecutter / 预处理器标记 | 多版本单代码库 |

同时盘点**项目约定**（写进环境卡的 `Project Conventions`）：注册方式、事件系统、网络通道、配置方案、命名风格、资源组织、测试目录。修改已有 mod 时必须沿用。

### 1.6 文档与周边

- `README` / `docs/` / `CHANGELOG`：声明的版本范围、开发步骤、发布方式。
- 既有 CI workflow、测试脚本 / 测试服目录：现成的验证路径，优先复用。

## 2. 输出：环境卡

模板与示例见 `SKILL.md` §3。每一行都要有证据来源（哪个文件哪一行），不允许"感觉是"。

## 3. 判定冲突与多目标

- 元数据与构建脚本不一致：以构建脚本 + 依赖树解析结果为准，并在环境卡里标注疑点。
- 多 Loader / 多版本：环境卡按任务目标模块填写；任务未指明时列出全部目标（各自完整一张卡，或一张汇总表），向用户确认或逐个处理。
- 完全无法判定：走 `SKILL.md` §13。

## 4. 交叉校验启发（辅助，不作为结论来源）

- Loader 与版本线：NeoForge 自 1.20.1 从 Forge 分叉，只存在于较新版本线；Forge 覆盖大量老版本；Quilt 基于 Fabric Loader 分叉；Fabric 几乎覆盖全部现代版本。
- `fabric-api` 版本号内嵌 MC 版本（形如 `0.141.6+1.21.11`）。
- Java 版本对照表（§1.4）。
- Loom 项目里 minecraft 依赖坐标就是 MC 版本本体。
