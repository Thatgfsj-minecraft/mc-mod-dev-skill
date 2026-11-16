# 分级热迭代：先选最低成本的那一层，再动手

> 适用环境：环境无关（Minecraft Java 模组开发通用流程）。示例命令以 MCB 参考实现（技能包 v0.5.1，bridge-mod 0.2.0；Fabric 1.21.11 桥接 + MCP Server）为具体载体；其他环境按 `debug-mcp.md` 的工具/op 对照表替换工具名。
> 验证方式：本文是流程约定 + 结构事实（原版行为经 1.21.11 反编译核实，逐条标注）。**耗时是量级估计**（同一台开发机、增量场景），不是基准测试；MCB 各 op 的运行时验证状态见 `environments/versions/1.21.11/bridge.md`。

## 1. 为什么要分级

改完一个文件就 `./gradlew build` + 重启客户端，一轮 2-5 分钟；其中绝大多数改动（数据包 JSON、贴图、模型、方法体里的一行坐标）**根本不需要重编译**。更糟的是全量重编译会把"资源路径写错"这类本该 10 秒暴露的问题，拖成几分钟一轮的盲试。

分级的原则只有一句：**用能验证这次改动的最小动作**。层级判断错了的代价不对称——高射炮打蚊子只是浪费时间，用低层级掩盖高层级改动则会得到"游戏行为没变"的假象。

## 2. 四级总表

| 层级 | 典型改动 | 触发 / 命令 | 耗时（量级） | 验收手段 |
|---|---|---|---|---|
| **L1 数据热重载** | `src/main/resources/data/**`：recipe、loot table、tags、advancement、datapack function、predicate | `mc_command({command:"reload"})`（集成服；等价 `/reload`） | 秒级（1 s 左右） | `mc_command` 返回 `success:true` + **功能性探针**（跑改过的 function / loot / tag 断言）；`mc_wait_log` 抓重载错误 |
| **L2 资源热重载** | `src/main/resources/assets/**`：PNG、model / blockstate / item model JSON、lang、font、particle、shader | `mc_reload_resources()`（桥接 0.2.0+，= 程序化 F3+T：`Minecraft.reloadResourcePacks()`）；老桥接只能用 F3+T（`keyDebugReloadResourcePacks`，默认 T，[实测：1.21.11 反编译]） | 1–3 s（随资源包体量） | `mc_logs` 搜加载错误（见 §6 规则 3）+ `mc_client_screenshot` 落盘截图 + `mc_screen_current` 确认无 Screen 遮挡 |
| **L3 代码热替换** | `src/main/java/**` 里**已有方法体**、常量、局部逻辑、GUI 坐标；不新增/删除方法或字段 | Debug 启动 `runClient` + JBR 增强重定义，IDE `Reload Changed Classes` | 秒级（~1 s 重定义） | 命令复测 + `mc_screen_current` / `mc_client_screenshot`；日志确认重定义成功 |
| **L4 完整重启** | 新增/改 `@Mixin`、类结构/字段签名/继承、注册表项、`fabric.mod.json`/`mods.toml`、依赖、版本号、Gradle 配置 | 停掉 `runClient` → 增量构建 → 重跑 | 1–3 min（增量构建 + 启动进世界） | 构建通过 + 启动日志 + 完整行为复测（见 `e2e-testing.md`） |

> 世界生成注册表是 L1 的例外：`dimension` / `dimension_type` / `biome` / `noise_settings` 等 JSON 在**世界装载时**读入并冻结，`/reload` **不会**重建它们（[实测：1.21.11 反编译] `ReloadCommand` 只重建数据包资源，不碰已冻结的注册表）。这类改动必须**重进世界**（新世界的注册表才生效），已生成区块也不会重新生成——按 L4 处理。

## 3. 各级怎么做

### L1 数据包

1. 改 `src/main/resources/data/<ns>/...`（1.21.x 的 tag 必须带命名空间：`data/<ns>/tags/...`，见 `pitfalls.md` C4）。
2. 确认资源已同步到运行 classpath：`runClient` 读的是 `build/resources/main`；在 IDE 外改文件时先 `./gradlew processResources`（或 IDE Build）。
3. `mc_command({command:"reload", side:"server"})`。**`/reload` 是异步的**：命令立即返回成功反馈，重载在后台 future 里完成（[实测：1.21.11 反编译] `ReloadCommand.reloadPacks` → `MinecraftServer.reloadResources`，没有稳定的"重载完成" INFO 日志）。
4. 验收用**功能性探针**而不是猜时间：`/function <改过的函数>` 看反馈、`/loot give @s loot <改过的表>` 看物品、`/execute if entity @s[tag=...]` 看 tag。重载失败会走 `Failed to execute reload`（WARN）——用 `mc_wait_log` 抓。
5. 数据包**没有**资源包那套"静默紫黑格"，但错路径 / 错 schema 的表现是"命令报解析错"或"注册表加载失败拒启"，比 L2 好排查。

### L2 资源包

1. 改 `assets/<ns>/...`；同样先确认 `processResources` 已同步。
2. `mc_reload_resources()` → 返回 `{reloaded, duration_ms}`；超时（30 s 看门狗）说明资源包大 / 着色器编译慢，如实记录。
3. **必须**扫一遍加载错误（规则 3）：`mc_logs({levelMin:"WARN"})`，正则用 §6 给出的真实字符串。
4. `mc_client_screenshot({filename:"l2-after.png"})` 落盘截图做前后对比（文件路径稳定，可跨步骤引用）；截图前先 `mc_screen_current()` 确认没有 PauseScreen/加载屏挡着。
5. 资源热重载只保证"资源管理器重载流程结束"，**不保证**每个模型/贴图都成功——所以第 3 步不可省。

### L3 代码热替换

1. 用 Debug 启动 `runClient`（IDEA / Eclipse 的 debug 配置），HotSwap 依赖调试 agent。
2. 只改**方法体内部**：局部变量、常量值、GUI 坐标、if 条件。增强重定义（JBR `-XX:+AllowEnhancedClassRedefinition`）可以多做一点（改方法体以外的部分有限支持），但**不要把它当 L4 用**。
3. 保存后在 IDE 触发 `Reload Changed Classes`（IntelliJ：`Run → Reload Changed Classes`，Debug 下通常已开启自动重载）。
4. 验收：命令复测 + `mc_screen_current` / `mc_client_screenshot`；重定义失败 IDE 会弹 `HotSwap failed` / `schema change not implemented`——按 §5 升级 L4，**不要反复重试**。
5. 已知边界：Mixin 在目标类加载时已织入，改 Mixin 类再热替换**不会重新织入**目标类；改 Mixin 一律 L4。改方法签名/字段/注解/枚举常量同样 L4（JVM 类 schema 变了）。

### L4 完整重启

1. **优雅停**：IDE 停止 / `/stop`（集成服退出）；不要强杀进程（理由与服务器相同，见 `e2e-testing.md` 重启注意）。
2. 增量构建：`./gradlew build`（或 IDE Rebuild），确认 `BUILD SUCCESSFUL`；产物与调试器类路径一致。
3. 重跑 `runClient`，进**一次性测试世界**（§7），按改动面做完整行为复测。
4. 若这轮是发布前验证，转到 `e2e-testing.md` 的专用服务器基线；L1/L2/L3 的快速验收**不能替代**回归。

## 4. 决策树

```text
这次改动落在哪里？
│
├─ 只有 src/main/resources/data/**.json（recipe/loot/tag/function/advancement）
│    ├─ 普通数据包资源 ─────────────────────────────► L1  /reload + 功能性探针
│    └─ dimension/biome/noise_settings 等世界生成注册表 ─► L4（重进世界，/reload 不重建）
│
├─ 只有 src/main/resources/assets/**（png/model/blockstates/lang/font）
│    └──────────────────────────────────────────────► L2  mc_reload_resources → 搜日志 → 截图
│
├─ src/main/java/** 只改已有方法体/常量/坐标（无 schema 变化）
│    └──────────────────────────────────────────────► L3  HotSwap → 命令复测 + mc_screen_current
│
└─ 其余（@Mixin / 新增类·方法·字段 / 签名·继承 / 注册表项 / mod 元数据 / 依赖 / 构建配置）
     └──────────────────────────────────────────────► L4  停客户端 → 增量构建 → 重跑

资源 + 代码混合改动：按最高层级走（改了 Java 就别指望 L2 能验证）。
```

## 5. 什么时候必须升级层级（反例清单）

| 你以为可以做 | 实际情况 | 正确动作 |
|---|---|---|
| 改了 biome / dimension JSON，`/reload` 一下 | 世界生成注册表已冻结，`/reload` 不重建 | 重进（新）世界；已生成区块不会变 |
| 改了 model JSON，F3+T 后画面没变 | 可能 resource 没同步到 `build/resources/main` | 先 `./gradlew processResources` 再重载；仍无变化按 L2 规则 3 搜日志 |
| HotSwap 后行为没变 | 改的是 `@Mixin` / 方法签名 / 字段——热替换不会重新织入/改 schema | 立即 L4，不重试 |
| IDE 报 `HotSwap failed: schema change not implemented` | 类结构变了 | 立即 L4 |
| 改完客户端 Java，重启客户端就行 | 注册表项（方块/物品/实体）在启动时注册 | L4（且要新世界或存档已注册过） |
| L1/L2 通过了就发布 | 快速验收只覆盖"这次改动" | 发布前仍跑专用服务器 E2E（`e2e-testing.md`） |

## 6. 三条硬规则

**规则 1：禁止盲目全量重编译。**
先按 §4 判断层级。L1/L2 不需要 Gradle 构建；只有 L3（需要 debug 启动）和 L4（schema/元数据变化）才值得付重启成本。反过来，**资源改动必须先确认 `processResources` 输出同步**：热重载读的是运行 classpath 上的文件，不是你的 `src/` 源文件——这是"改了没反应"的头号原因。

**规则 2：HotSwap 报 schema change，立即升级 L4。**
`schema change not implemented` / `class redefinition failed` / `attempted to change class hierarchy` 出现时，说明这次改动超出了 JVM 重定义的边界。反复重试不会成功，只会浪费时间并让你误判"代码没写对"。

**规则 3：L2 之后必须做静默失败拦截。**
资源加载失败**经常不崩游戏**：模型缺失退化成紫黑格 / 空模型，贴图缺失退化成 missing sprite，语言键缺失退化成原始 key。必须主动搜日志（MCB 工具 `mc_logs`，协议 op 即 `log.history`；以下是 1.21.11 反编译核实的真实文本）：

```text
mc_logs({ levelMin: "WARN" })
正则：Failed to load model|Missing model for variant|Missing textures? in model|Missing texture references
     |Using missing texture|Unable to load font
```

- `Failed to load model {id}`（ERROR，`ModelManager`）：模型 JSON 解析/烘焙失败。
- `Missing model for variant: '{blockstate}'`（WARN，`ModelManager`）：blockstate 指向的 model 不存在。
- `Missing textures in model` / `Missing texture references in model`（WARN，`ModelManager`）：模型引用的贴图/材质槽缺失。
- `Using missing texture, unable to load {id}`（ERROR，`SpriteResourceLoader`）：图集找不到声明的贴图文件。
- `Unable to load font ...`（WARN，`FontManager`）：字体 provider 失败。

> 旧版本 / 其他 Loader 的措辞可能不同（例如 `Unable to load model`、`Unknown item` 一类历史文案），检索时用宽正则：`Missing|Unable to load|Failed to load model|Unknown`，不要把 1.21.11 的字符串当跨版本契约。

## 7. 一次性测试世界纪律

L1–L3 都允许在**正在运行的世界里**改行为，代价是测试状态会污染存档：

- 用**专用开发世界**（创造模式 + 超平坦或固定种子），不要在主生存存档上做热迭代；`/reload` 期间坏掉的 datapack 可能让存档处于半加载状态。
- 每次实验前记录基线（`mc_screen_current` + 一张 `mc_client_screenshot` 落盘），失败后可对比"是这次改动导致"还是原本如此。
- MCB 命令是四级权限（`kill @e` / 大范围 `fill` 都执行得出去），一次性世界的纪律同样适用于命令搭场景（`debug-mcp.md` §4 第 6 条）。
- 热迭代频繁的世界建议直接建新的（`run/saves/` 下留档），坏档比排查便宜。

## 8. 与 E2E 的关系

| 用途 | 用什么 |
|---|---|
| 开发循环里"改完立刻看效果" | L1–L3 + MCB（本文） |
| 提交前 / 发布前回归 | 专用服务器 + 服务端权威断言（`e2e-testing.md`），基线不变 |
| 客户端 UI / 渲染验收 | L2 + `mc_client_screenshot` 落盘截图；自动化客户端预置见 `e2e-testing.md`「客户端 run 目录预置」 |

分级热迭代是**开发效率工具**，不是测试基线：它回答"这次改动生效了吗"，不回答"整个模组还能用吗"。后者仍然是 `e2e-testing.md` 的回归范围（`SKILL.md` §10）。
