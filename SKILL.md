---
name: minecraft-mod-dev
description: Minecraft Java 模组开发通用技能（项目环境感知）：开发、修改、移植、调试、测试或发布任何 Minecraft 模组之前，先识别目标项目的 Minecraft 版本、Mod Loader、映射体系、Java 版本、构建系统与项目架构，再按实际环境实现、构建、测试与排查；不绑定固定版本或加载器。Use when developing, modifying, porting, debugging, building or testing any Minecraft Java mod — first detect the project's MC version, mod loader, mappings, Java and build system, then implement and verify against that actual environment.
---

# Minecraft 模组开发 · Agent 操作手册

本 Skill 是**环境无关**的操作规程：核心负责"怎么发现项目环境、怎么判断、怎么开发、怎么验证"；某个具体版本 / Loader / 映射 / 构建系统的具体知识放在 `references/environments/` 下按需加载。

```
Skill Core（本文件）
   ↓
Phase 0 — Project Discovery（读项目文件）
   ↓
Environment Identification（版本 / Loader / 映射 / Java / 构建系统 / 架构）
   ↓
按需加载 references/environments/ 对应知识
   ↓
Understand → Implement → Build → Test / E2E → Debug
```

**版本和 Loader 是 Agent 开始工作后从项目里识别出来的"项目事实"，不是 Skill 可以预先假定的"开发前提"。**

## 1. Purpose

- 适用：任何 Minecraft Java 模组的**新建、修改、移植、调试、测试、发布**。不预设 Minecraft 版本、Loader、映射或构建系统。
- 三个不变式贯穿一切任务：
  1. **环境先于代码**——Phase 0 环境识别发生在任何实现之前；
  2. **不凭记忆猜 API**——有不确定就必须核实（§7）；
  3. **验证只信目标环境**——构建 + 专用服务器行为断言；客户端 / 机器人状态不作为事实来源。

## 2. Core Principles

1. **不要凭记忆写 Minecraft 代码**。对 API 是否存在、类名、方法签名、事件生命周期、注册机制、网络 / 命令 API 中任何一项存在不确定时，不允许仅凭模型记忆直接实现——跨版本甚至小版本间 API 都会改名、改签名、改语义。核实手段见 §7 与 `references/api-verification.md`。
2. **交互逻辑一律服务端权威**。客户端事件处理器永远放行（如 Fabric 回调返回 `PASS`、NeoForge 不取消事件），让原版逻辑照常流动；客户端伪造"成功"会吞掉后续交互包，产生"界面闪一下就关"这类难查 bug。（类名为 Mojmap 写法；其他映射下名称不同，概念一致。）
3. **行为验证只信目标环境里的专用服务器 + 服务端权威断言**。客户端机器人（如 mineflayer）状态在部分版本有解析缺陷，不可作为断言依据（见 `references/e2e-testing.md`）。
4. **结论必须落到证据**。查复杂 bug 时让子代理读源码调查、主代理审结论，不要脑内推演；结论要落到"文件:行号"。
5. **已验证经验分环境记录**。具体版本 / Loader 的差异和坑写进 `references/environments/` 并标注适用范围；只有跨环境验证过的结论才能写成通用规则。

## 3. Project Discovery（Phase 0 · 强制前置）

**Agent 在开始任何 Minecraft Mod 开发、修改、调试或测试任务之前，首先识别当前目标项目的实际开发环境。** 不允许先写代码再补判断。

至少检查（完整判定方法见 `references/environment-discovery.md`）：

- 构建配置：`gradle.properties`、`build.gradle(.kts)`、`settings.gradle(.kts)`、`gradle/libs.versions.toml`
- Loader 元数据：`fabric.mod.json`、`quilt.mod.json`、`META-INF/neoforge.mods.toml`、`META-INF/mods.toml` 及其他 loader metadata
- 映射配置（Mojmap / Yarn / Parchment / MCP…）、Java toolchain、source set、项目模块划分
- `README` / 开发文档、已有代码（包名与类名是映射体系和项目约定的直接证据）

检查完成后输出**环境卡**，再开始实现：

```
Minecraft Version:   1.20.1
Loader:              Forge
Mappings:            Mojang
Java:                17
Build System:        ForgeGradle
Architecture:        single-loader
Relevant Modules:    …
Project Conventions: 注册方式 / 事件系统 / 网络 / 配置 / 命名 …
```

```
Minecraft Version:   1.21.1
Loader:              Fabric
Mappings:            Yarn
Java:                21
Build System:        Fabric Loom
Architecture:        common + fabric
Relevant Modules:    …
Project Conventions: …
```

（以上两张为**格式示例**，仅演示写法——数值一律以实际侦察为准，不要照抄。）

规则：

- **版本识别必须先于代码编写**。同一功能在不同版本 / Loader 下可能有不同的类名、方法签名、注册方式、事件与网络 API、`ResourceLocation`/`Identifier` 类 API、Server/Client 类型归属与数据组件机制。
- 项目同时存在多个 Loader / 多个版本时，按任务目标识别**实际目标模块**并逐个处理；任务未指明时列出全部目标向用户确认，**不擅自替用户选一个**；无法交互时逐个目标分别完成并分别验证，或选与任务最相关的目标并显式声明选择依据。
- **新建项目（无现有代码可侦察）**：环境卡由任务规格 + 目标版本的版本卡（若有）填充；用户未指定的自由维度（映射体系、mod id、包名、架构模式）一次性列出请用户确认，或选默认并显式声明理由，不逐项停机（NeoForge / Forge 的映射事实上是 Mojmap；Fabric 的 Yarn 与 Mojmap 都常见，不确定时问一次）。
- 环境卡输出到任务 / 会话记录并随上下文传递，**未经用户要求不写入用户仓库**；每行注明证据来源（见 `references/environment-discovery.md` §2）。
- 关键信号冲突或无法判定时停下（§13），禁止默默采用默认假设。

## 4. Environment Identification

环境卡的七个维度都要给出结论（判定细节见 `references/environment-discovery.md`）：

| 维度 | 为什么影响开发 |
|---|---|
| Minecraft 目标版本 | 决定原版 API 面貌（类名、签名、语义、机制增删） |
| Mod Loader / Platform | 决定入口、事件系统、网络、生命周期 API（Fabric / NeoForge / Forge / Quilt / 其他） |
| 映射体系 | 决定类名与方法名的"写法"（Mojmap / Yarn / Quilt-mappings / Parchment 叠加 / MCP…） |
| Java 版本 | 语法上限与工具链要求 |
| 构建系统 | 决定依赖配置、映射接入、remap、运行方式（Loom / ModDevGradle / ForgeGradle / Unimined / Stonecutter…） |
| 架构 | single-loader、多 Loader（common + platform 或复制式多构建）、多版本单代码库——决定改动要落几处 |
| 项目约定 | 已有代码的注册方式、事件系统、网络通道、配置、命名——**修改已有 mod 必须沿用项目自己的写法** |

## 5. Detection Signals（速查）

最快的几个信号（完整清单见 `references/environment-discovery.md`）：

- `src/main/resources/fabric.mod.json` → Fabric；`quilt.mod.json` → Quilt；`META-INF/neoforge.mods.toml` → NeoForge（较新版本线）；`META-INF/mods.toml` → Forge（或早期 NeoForge）
- `fabric.mod.json` 的 `depends.minecraft` 直接给出目标版本范围
- Gradle 插件：`fabric-loom` → Fabric Loom；`net.neoforged.moddev` → ModDevGradle；`net.minecraftforge.gradle` → ForgeGradle
- 映射：`loom.officialMojangMappings()` / `mappings channel: 'official'` → Mojmap；`net.fabricmc:yarn` → Yarn
- 代码内判据：`net.minecraft.world.item.ItemStack` → Mojmap；`net.minecraft.item.ItemStack` → Yarn。**看完整包路径**：新版本 Mojmap 自己也会改名（已验证 1.21.11 线 `ResourceLocation` → `Identifier`，但包仍在 `net.minecraft.resources`），包路径比类名可靠。
- 识别出版本号后，若 `references/environments/versions/<版本>/README.md` 版本卡存在，用它交叉校验 Java / Loader / 映射可用性。

## 6. Development Workflow

每个任务按此顺序推进，前一道关没过不进下一道：

1. **Understand**：Phase 0 环境卡 + 读目标功能相关代码，弄清现有实现与项目约定。
2. **Implement**：遵守 §8 实现规则；所有 API 经过 §7 核实。
3. **Build**：§9，目标环境的全部构建都要编译通过。
4. **Test / E2E**：§10，测试方式跟随目标环境。
5. **Debug**：§11。
6. **回归**：改动完成后重跑既有测试 / 断言全集，验证没有破坏已有功能；多构建项目在每个目标构建上跑。

## 7. API Verification（不凭记忆猜 API）

**触发条件**：对"API 是否存在、方法签名、类名、事件触发时机与生命周期、注册机制、网络协议、命令 API、资源定位构造、Server/Client 类型归属、数据组件机制"中的任何一项存在不确定。

**核实优先级**（依次尝试，全部无果才动手）：

1. 检查项目现有代码（同类功能怎么写的）
2. 检查当前项目依赖（依赖树 + Gradle 缓存里的 jar）
3. 检查映射源码（反编译 sources jar 直接读）
4. 检查 Minecraft / Loader 源码（机制内部约束必须反编译确认：入口方法的前置读取、每 tick 校验）
5. 检查官方或项目文档
6. 仍不确定 → 写最小编译探针验证假设，再实现

具体手法（如何找 jar、如何读源码、如何用映射查询工具、常见记忆陷阱清单）见 `references/api-verification.md`。

## 8. Implementation Rules

- **沿用项目约定**：注册、事件、网络、配置、命名都跟项目现状走；不要在别人的项目里引入第二套写法。
- **加载器隔离（多 Loader 项目）**：若项目采用"core + 各 Loader 入口"结构，保持 core 不 import 任何加载器类，加载器差异收敛到入口层；是否抽公共库由项目现有架构决定，不擅自重构。
- **从零搭多版本 / 多 Loader 项目时**，常见架构模式（按复杂度递增）：
  1. 单项目单 Loader（绝大多数场景）
  2. core + 各 Loader 入口（单项目或少量子项目）
  3. common + platform 双模块（Architectury 式）
  4. 每个版本 × Loader 一套完整独立项目（复制式 core：可独立编译、`diff` 两目录直接得到差异清单；代价是同一改动落多处）
  5. 多版本单代码库 + 预处理器（Stonecutter 等）

  组织内验证过模式 4，实测细节见 `references/environments/loaders/fabric-vs-neoforge-1.21.x.md`。
- **交互类功能**：服务端权威（§2.2）；接管右键前先给有自己界面的原版方块让路。
- **模仿原版行为的功能**：先反编译原版确认它的前置校验和每 tick 校验（§7 第 4 条），再设计"满足前置 → 建临时物 → tick 级清理器"。适用于复刻原版方块 / 物品**完整行为链**的功能（如便携床、随身容器）；简单功能不要套用整套模式。
- **数据安全**：写回型数据结构用引用相等判断有效性、打开前做容量上限检查、拒绝嵌套写入；临时方块 / 实体的清理要抑制掉落防复制。
- **配置解析**：逐字段手写解析 + 显式默认值 + 越界归一化，不依赖 Gson 自动反序列化。
- **mod 兼容 tags 用可选引用**：`{ "id": "#c:<类别>", "required": false }`——目标环境没有该标签也不报错；建议覆盖全部原版变体。
  （以上涉及原版机制条目的类名为 Mojmap 写法；其他映射下名称不同、概念一致，动手前按目标项目映射核实。）

## 9. Build Verification（构建与发布）

- 用项目自己的构建入口（通常 `./gradlew build` 或等价任务）。首次构建先检查是否需要隔离 `GRADLE_USER_HOME`——**关键**：全局镜像 init 脚本会直接弄坏 Loader 插件解析（实测必现，不是"可能"），见 `references/pitfalls.md` A2/A3。
- 构建失败 = API 假设错误的信号：把每个错误按 §7 归类核实，不要凭感觉盲改。
- 多构建项目：**每个目标构建都要编译通过**；版本号更新要落到项目实际存版本的所有位置（单项目一处，多构建项目按其布局逐处）。
- 发布：统一升版本号 → 全部目标构建产物（Gradle 默认输出 `build/libs/<archivesName>-<version>.jar`）→ 测试服部署 + E2E 全绿 → 提交并打 tag（tag 风格 `vx.y.z`）→ `git push` 时**带 `--tags`**（否则 tag 不会上传）→ `gh release create vx.y.z --title … --notes … <全部目标构建的 jar 路径>`（release notes 列变更与验证方式）。CI 注意事项见 `references/pitfalls.md` A4。

## 10. Testing / E2E

- **测试方式跟随目标环境**：先问"这个版本 / Loader / 项目结构的标准验证路径是什么"，再搭测试。服务器安装方式随 Loader 不同（Fabric launcher / NeoForge 与 Forge installer），命令面（RCON、`/execute`、`/data`）是原版能力、与 Loader 无关。
- 有玩家交互行为的 mod：**专用服务器 + 服务端权威断言**是基线；客户端机器人只做操作、不做状态断言。
- 建议模组带**启动自检**：服务端启动时跑一次核心数据读写回环，日志输出 `SELF-TEST PASS`——任何版本升级、映射变化都能第一时间暴露数据层问题。
- 测试架构、服务器搭建、断言模式与踩坑（RCON 探针、区块加载、机器人协议）见 `references/e2e-testing.md`。

## 11. Debugging

- 从日志出发 → 反编译确认内部约束 → 结论落到"文件:行号"。
- 复杂 bug 派子代理读源码调查，主代理审证据链。
- "某平台独有 bug"大概率是漏改一处：先 diff 各构建的同源文件，再怀疑逻辑本身。
- 行为级差异（编译通过但表现错）只有行为测试能暴露——回到 §10。

## 12. Reference Loading Strategy

按需加载，不通读所有环境知识：

```
先识别目标环境（Phase 0）
      ↓
只读取匹配的 references/environments/ 文件
      ↓
需要跨版本 / 跨 Loader 比较（迁移任务）时，再读取其他环境文件
```

- 默认可读（通用方法层）：本文件 + `references/environment-discovery.md` + `references/api-verification.md` + `references/pitfalls.md` 第一部分 + `references/e2e-testing.md`。
- 环境知识层（`references/environments/`）：
  - 版本：`versions/<mc版本>/README.md` 版本卡 + `versions/<旧版本>/vs-<新版本>-<映射>.md` 已验证版本对差异。**覆盖清单的唯一事实来源是 `references/environments/versions/README.md` 的覆盖表**（新增版本不需要修改本文件）。
  - Loader：`loaders/<loaderA>-vs-<loaderB>-<版本线>.md`。
  - 版本卡里未标注"已验证"的内容只是结构事实，不能当验证结论用。覆盖范围**不代表支持边界**，没有对应文件时按 §13 处理；无文件夹的版本可读同发布线最近邻版本的版本卡（见 versions/README.md 的缺号对照）。
  - **迁移目标落在两个已验证端点之间时**（如 1.21.1 → 1.21.8）：读取夹持的版本对差异文件作为候选清单，并逐条核实各条目在目标版本是否成立（差异文件内标注了各条目的适用边界）。
- 完整加载规则与扩展约定见 `references/README.md`。

## 13. Failure Handling

| 情况 | 处理 |
|---|---|
| 环境无法确定（信号冲突 / 缺失） | 停下，列出疑点与已检查证据，向用户确认；**禁止默认假设** |
| 项目是多 Loader / 多版本，任务未指明目标 | 列出全部目标请用户指定；或逐个目标分别完成并分别验证 |
| 目标环境没有对应 reference | 声明"本环境无已验证知识"，全程走 §7 核实流程，小步实现 + 尽早构建验证 |
| 行为测试暴露新的环境差异 | 按 `references/README.md` 的约定沉淀进对应 env 文件（或新建），注明验证方式 |
| 构建环境故障（镜像 / wrapper / JVM 版本） | 先查 `references/pitfalls.md` 第一部分 A 组 |
| 现有功能被改动破坏 | 回滚该改动，重新走 Understand → 最小增量修改 → 回归 |
