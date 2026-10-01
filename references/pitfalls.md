# 踩坑手册

分两部分：

- **第一部分：通用坑**——条目标注了适用范围（如 `[Fabric Loom]`、`[1.21.x]`）的只在该范围内成立；未标注范围为跨环境成立。标签图例：`[构建系统：X]`、`[X × Y]` 标注**环境范围**；`[领域·子域]` 标注条目主题。涉及原版机制与 Loader API 的类名 / 代码片段均以条目标注的映射 / Loader 写法为准，其他映射 / Loader 下名称不同、概念一致（动手前按目标项目核实）。
- **第二部分：案例研究**——来自组织实战项目的特有机制 bug，**结论不可复用到别的 mod**；保留价值在于示范排查方法。

---

## 第一部分：通用坑

### 构建环境

**A1. [构建系统：Fabric Loom] Loom 1.18+ 报 "requires JVM 25"**
升级 fabric-loom 到 1.18+ 后 Gradle 起不来：Loom 1.18 改用 Java 25 的 FFM API 实现 native 层，Gradle JVM 必须 25+——**任何 MC 版本线都受此影响**，不只 1.21.x（来源：Loom 1.18 release notes）。

**A1b. [Fabric Loom × 1.21.x 目标] 推荐钉版组合**
做 MC 1.21.x 目标时钉 `fabric-loom 1.17.21` + Gradle 9.5.1 + JDK 21，不要顺手升级（组织实测组合）。
**MC 26.x 目标**（去混淆化时代）换轨：`net.fabricmc.fabric-loom` **1.18.2**（新插件 id，删 mappings 行，`implementation` 代 `modImplementation`）+ Gradle ≥9.7 + **JDK 25** + ModDevGradle 2.0.147（无需升级）。详见 `environments/versions/1.21.11/vs-26.x-deobf.md` §1。

**A8. [26.x] 去混淆化是工具链断层，不是普通 API 升级**
[实测：26.1.2 / 26.2 / 26.3 三线] Mojang 自 26.x 停发混淆映射（version JSON 无 client/server_mappings），jar 本体即 mojang 名。症状链：Loom ≤1.17 报 "Failed to find official mojang mappings"（换 mappings 写法无用，必须换 Loom 1.18+ 与新插件 id）；升 Loom 后报 "Minecraft 26.x requires Java 25 but Gradle is using 21"（daemon/toolchain/服务器三者都要 25）；再报 plugin-api 9.7.0 解析失败（wrapper 升 ≥9.7）。**先过工具链断层再谈 API**，别在旧 Loom 上耗。

**A9. [Gradle 通用] daemon 元数据残留已删除的 JDK → "supplied javaHome seems to be invalid"**
[实测：26.x 移植期] 并行/先前构建用临时 JDK（后被删除）启动过 daemon 时，GRADLE_USER_HOME 的 daemon 注册表仍指向旧 javaHome，后续所有无关构建（含 1.21.x 旧工程）统一失败在 "Tried location: <已删路径>\bin\java.exe"。修复：`./gradlew --stop` 杀掉全部 daemon 即愈（daemon JVM 元数据不随 JDK 删除清理）。全工程同时挂同一错误时先查这个，别怀疑工程本身。

**A10. [ModDevGradle × Gradle 9] 无本机 toolchain 时配置期崩（IBM_SEMERU）**
[实测：26.3] 本机无可探测的 MC 要求版本 toolchain（如 JDK 25）时，MDG 2.0.147 走供应商下载路径引用 `JvmVendorSpec.IBM_SEMERU`，该枚举 Gradle 9 已删 → 配置期直接崩。修复：先装好 JDK 并让 JAVA_HOME/PATH 可被探测（foojay resolver 或自装），不触发下载路径即无碍。

**A11. [老版本线] 1.7.10/1.12.2 移植杂坑集**
[实测：2026-10-01 skyislands 老线移植]
- **WorldType 名上限 16 字符**：`skyislands_classic`（18 字符）直接崩——命名前先确认上限（1.12.2/1.7.10 都是）。
- **老 Gradle（2.x/4.x）不继承系统代理**：直连 maven.minecraftforge.net 读超时；需要代理时必须显式 `-Dhttps.proxyHost/-Dhttps.proxyPort`（老 FG 的仓库重排 + `--offline` 见各版本卡）。
- **1.7.10 universal jar 不带 bootstrap**：手铺 vanilla server.jar（piston sha1 校验）+ launchwrapper-1.12 + asm-all-5.0.3 + jopt-simple-4.5 + lzma-0.0.1，主类 `cpw.mods.fml.relauncher.ServerLaunchWrapper`。
- **改了源码必须重建**：老线 jar 无 CI 兜底，"上代崩后只改了 A 侧、B 侧 jar 还是旧的"这类陈旧产物事故真实发生过（启动即崩，排查先对 jar 内 class 与源码的时间戳）。

**A2. [Gradle 通用] 全局 init 脚本 / 镜像弄坏插件解析**
机器全局 `~/.gradle/init.gradle` 强制镜像（如阿里云）时，Loader 插件 / 依赖在镜像上不存在就**必然解析失败**（组织实测：NeoForge 插件在阿里云镜像上解析失败）。修复：`GRADLE_USER_HOME=/干净目录 ./gradlew build`（目录里不能有 init.gradle）。

**A3. [Gradle 通用] wrapper 首跑下载超时**
隔离 GRADLE_USER_HOME 后 services.gradle.org 下载超时。修复：从 `~/.gradle/wrapper/dists/` 预拷对应发行包（如 `gradle-9.5.1-bin`）到隔离目录同名路径。

**A4. [CI 通用] 两连坑**
① 推 `.github/workflows/` 需要 token 有 `workflow` scope（`gh auth refresh -h github.com -s workflow`），否则先放 `ci/` 目录；② upload-artifact v4 的 **artifact 名不允许 `/`**——matrix 里项目路径和 artifact 名用两条平行数组。

**A5. [老版本线] 老 Forge 工具链组合约束**
老 Forge（ForgeGradle 2/3 时代，1.12.2 及更早）对 Gradle / JDK / mappings 的组合有严格且过时的要求，不要凭记忆升级任何一环；以项目现状与对应年代官方文档为准。识别方法见 `environment-discovery.md` §1.3（MCP / SRG 信号）。

**A6. [NeoForge] 某些版本线只有 beta（"取最新正式版"会扑空）**
[实测：1.21.9 线] maven.neoforged.net/releases 上 21.9.x 全部是 `-beta`（至 21.9.16-beta），没有任何正式版；而 21.10 线正式版正常。选坐标时不要假设"每条线都有 stable"：先抓 `maven-metadata.xml` 全量列表，可疑时用 versions JSON API + 无后缀 pom 直探双源核实；确实只有 beta 时在依赖坐标里显式钉 beta 并注明。

**A7. [自动化/子代理执行] 前台长命令会撞"无活动"看门狗**
自动化环境里常有无活动看门狗（如 10 分钟无工具活动即终止执行者）。gradle 冷构建（NeoForge 首跑 NFRT 可 20 分钟+）和 MC 服务器进程绝不能前台裸跑：一律后台启动 + 输出重定向到日志文件 + 短轮询跟进。另外**后台命令里用绝对路径**——后台任务继承的工作目录可能与派发时不同，相对路径会静默打错目标（实测发生过"想建 A 仓库实际建了 B 仓库"，exit 0 无报错）。

**A11. [测试环境] fabric server launcher 走 launchermeta.mojang.com 连不通**
[实测：1.21.4 移植批次] 本机 piston-meta 可达但 launchermeta 不通时，fabric server launcher 首跑会卡在下载原版 server jar。修复：按 piston-meta 给出的 SHA1 预先下载原版 server jar，放 `versions/<mc>/server-<mc>.jar`，launcher 检测到即跳过下载。

### 交互模型

**B1. [通用] 客户端伪造成功会吞掉后续交互**
客户端事件处理器返回"成功"（如 Fabric 回调返回 `SUCCESS`、NeoForge / Forge 客户端侧取消并给结果）会让客户端认为已完成、不再发 use 包到服务端，表现为"界面闪一下就关""功能时灵时不灵"。规则：客户端一律放行（Fabric 返回 `PASS`、NeoForge / Forge 不取消），所有真实动作在服务端做。（Forge 线无本组织已验证的 loader 级知识，事件分侧语义动手前按 api-verification 核实。）

**B2. [通用·原版容器机制] 没有方块锚点的菜单闪关**
从物品 / 远程打开任何菜单（便携工作台、随身容器类功能），原版 `stillValid(ContainerLevelAccess)` 每 tick 检查锚点方块类型，玩家脚下不是对应方块就立即关闭。子类覆写 `stillValid(p) { return p.isAlive(); }`。

**B3. [通用] 与原版方块行为冲突**
接管右键前先让路：`level.getBlockState(pos).getMenuProvider(level, pos) != null` 说明方块有自己的界面，保持原版行为。

**B4. [通用] 假玩家（自动化）误触**
判定 fake player 用 `player.getClass() != ServerPlayer.class`（精确比较，防子类绕过），并给配置开关；别用 `instanceof`。

**B5. [通用] 潜行语义组合**
用 XOR 门表达"默认不潜行触发、潜行放置；配置反转"：`if (config.requireSneak != player.isShiftKeyDown()) return PASS;`——永不出现两种都触发 / 都不触发。（`return PASS` 为 Fabric 回调写法；NeoForge / Forge 对应"不取消事件"。）

**B6. [通用·事件总线] EventBus 世代差异：Class 令牌注册在 1.7.10 静默零监听器**
[实测：1.7.10 字节码确认] 1.12.2 的 `EventBus.register(Object)` 有 `target.getClass()==Class.class` 静态分支（注册静态 @SubscribeEvent 方法）；**1.7.10 没有**——传 Class 令牌会扫描 `java.lang.Class` 自身方法，静默注册零监听器，且实例方法即使扫到也无法调用。症状：编译全绿、启动无错、事件处理器永远不执行（"功能整体失效"类 bug）。规则：1.7.10 一律注册**实例**（单例 `public static final X INSTANCE = new X();` + `register(INSTANCE)`）；跨老版本写事件注册前先反编译当世代 EventBus 确认分支。

### 数据安全

**C1. [通用] 配置项静默复位**
依赖 Gson 自动反序列化时构造器不运行，缺键 / 旧文件会让字段回到非预期值。逐字段手写解析 + 显式默认值 + 越界归一化（如 `forceRows` 不在 1..6 就回 -1）。

**C2. [通用] 引用相等 vs 组件相等**
以物品组件为存储的容器，用 `Inventory.contains` 之类做有效性判断会被"内容相同的另一个物品"顶替——有效性判断用**引用相等**逐槽扫描。

**C3. [通用] 容量上限静默丢数据**
任何"写入有硬上限的存储"的功能，打开前必须检查容量（用 EntityBlock 探针或组件计数），超限拒绝并提示（en_us / zh_cn 双语），绝不能静默截断。

**C4. [数据包] `data/tags/...` 不是新 tag 路径，会让服务器拒启**
[实测：1.21.5] 把 datapack 里的 tag 放到 `data/tags/worldgen/world_preset/normal.json`（少了命名空间段）时，`data/` 后第一段被解析为**命名空间 `tags`**，该文件被当成 `tags:normal` 这个 world_preset 的定义去解析（报 `No key dimensions`），注册表加载失败**直接拒绝开机**。tag 路径在任何 1.21.x 都必须是 `data/<命名空间>/tags/...`；错误信息的"world_preset"字样极具误导性，先查路径再查内容。

### 测试

**D1. [测试·版本相关] mineflayer 客户端解析崩溃（1.21.x 实测）**
症状：`PartialReadError`（ArmorTrimMaterial 等），机器人状态错乱。根因：minecraft-data 的协议定义与服务器实际物品组件不同步。修复：架构上放弃客户端断言——机器人只做 join / look / 点击，所有断言走 RCON；bot 协议钉具体版本号。服务器日志零异常 = 服侧无 bug 的必要条件。（该缺陷在 1.21.x 实测出现；"断言只走服务端"的架构原则跨环境成立。协议覆盖面实测：mineflayer 4.39.0 支持 1.21.4–1.21.10（协议 767–773）与 **26.1**（775）；**26.2 / 26.3 缺协议定义**（认识版本号但 "No data available"/"unsupported protocol version"），bot 只能跳过，RCON 断言不受影响。）

**D2. [测试·RCON 通用] RCON `run say` 探针永远"失败"**
`execute if block ... run say MATCHED` 的 say 输出走聊天广播，**RCON 响应为空串**——无论条件真假，断言恒假。用裸 `execute if block <pos> <方块>[<状态>]`，响应是内联 `Test passed` / `Test failed`。
经验：**先验证测试工具自身**——写任何方块断言前对已知方块（如超平坦 0 -64 0 的 bedrock）跑探针自检。

**D3. [测试·RCON 通用] 嵌套 `execute` 多打一个斜杠 = 静默假 FAIL**
[实测：1.21.8] `execute in <dim> run /execute if block ...`（run 后多了斜杠）返回 `Incorrect argument` 而非空串或报错——断言框架只认 "Test passed" 时表现为静默假 FAIL，曾一轮误判 8 个探针。规则：拼接嵌套命令时剥掉内部斜杠；**每条 RCON 响应原样落盘**，事后能归因"命令错"还是"行为错"。

**D4. [测试·服务器进程] 重启判据用"进程退出"，不是"端口关闭"**
`/stop` 后游戏端口先关，world 保存（尤其 forceload 过的下界/末地维度）可持续**数分钟**（实测带 forceload 的世界 5-7 分钟，但会正常完成）——耐心轮询进程退出，勿提前 taskkill；老进程还持有 `session.lock` 时起新实例直接崩 `IOException: 另一个程序已锁定文件的一部分`。Git Bash 下查进程用 `tasklist //FI "PID eq N"`（单斜杠轮询不稳定）。

**D5. [测试·服务器进程] watchdog 强杀会留 0 字节 .mca，坏世界级联崩服**
[实测：1.21.8] 看门狗强杀后 `r.0.1.mca` 等区域文件被创建但为空，之后任何触及该 region 的加载永久挂起，形成"坏世界级联崩服"，且与 mod 无关（栈里无 mod 帧）。规则：强杀过进程就**删掉该测试世界再跑下一轮**；注意下界存档在 `world/DIM-1/`，不在 `world/the_nether/`。

**D6. [测试·机器人] bot 传送进未生成区块会锁死服务器 tick**
[实测：1.21.8 三轮复现] 玩家移动包触发 `Entity.move → getFluidState → ServerChunkCache.getChunk → managedBlock`，而区块任务要靠 tick 推进 → 死锁 → watchdog 60s 崩服。mineflayer bot 远距离传送/下坠前，**先在控制台 `/forceload` 目标区块并探针确认已加载**。另：`forceload` 状态本身跨重启持久（world saved data），重启后别假设"本会话没 forceload 过"。

**D7. [测试·虚空维度] 虚空下界里连控制台 forceload 都可能死锁**
[实测：1.21.4 / 1.21.5 两轮复现，栈一致] 在自定义虚空 `noise_settings` 的下界里，`/forceload add <远区块>` 或 `tp @s <远坐标>` 本身就会触发 `ServerChunkCache.getChunk → managedBlock` 永久 park（60s 后 watchdog 崩服）——**D6 的"先 forceload 再传送"在虚空维度不成立，forceload 自己就是死锁源**（bot 在不在场都不救；tp 死锁时该 RCON 命令返回空串、后续命令仍被泵响应）。规则：命令驱动的区块操作只落在出生点附近已生成区块；"另一地点"类断言用 chunk 0,0 附近的坐标（如 2 70 2）替代几百格外远点。

**D8. [测试·E2E] 探针/断言跨版本不可移植清单（1.21.x–26.x 实测）**
- **"物品探针法"（container.N 塞探针物品）在 1.21.4 不可用**：`/item replace entity <玩家> container.N` 在 1.21.4 寻址玩家背包（0-8=快捷栏），1.21.11 才寻址打开的菜单——替代：断言用服务端下发的窗口类型+格数+无 windowClose，写路径用 bot 真实点击后 RCON NBT 验证。
- **数据组件语法 1.21.4**：`container=[{slot:0,item:{...}}]` 必须显式 `slot`（1.21.5+ 可省略；26.2 的 `/item replace` 同样要求 slot）。
- **mineflayer 窗口类型串**按前缀断言：附魔=`minecraft:enchantment`、锻造=`minecraft:smithing`、潜影盒菜单/末影箱=`minecraft:generic_9xN`——勿用方块名。
- **`/give` 紧跟 `/item replace weapon.mainhand` 会吞掉给的物品**（give 落所选槽被 replace 覆盖）：先持械再 give。
- **多版本轮换测试服的 RCON 端口互锁**：关机时游戏端口先释放、RCON 端口保持到进程退出——下一实例能绑游戏端口但 RCON 初始化失败（日志 "Unable to initialise RCON"），随后命令打到**还在关机的上一世界**产生"世界冻住"假象。起服前等两个端口都空闲 + Done 后检查无 RCON bind 失败 + 只跟踪自己的 PID。
- **Windows 中文 locale 服务器日志是 GBK**：grep 视为 binary 静默失配——一律 `grep -a`。
- **superflat 测试世界需显式 `generator-settings={"layers":[...]}`**：空 `{}` 在 1.21.4+ 报 "No key layers"。
- **mineflayer 26.1 菜单伪影**：bot 在场时服务端菜单 0.5–3s 内被关（stillValid 全 true、客户端无 close_container 包）——菜单槽探针在 26.1 不可用，以服务端自证（SELF-TEST/逐 tick 打点）替代，勿误判为模组缺陷（D1 同类）。
- **Corretto 25 on Windows 偶发 JIT 崩溃**（`EXCEPTION_ACCESS_VIOLATION`，vanilla chunk 序列化帧无 mod 帧）：加 `-XX:TieredStopAtLevel=1` 规避；崩后删档（D5）。

**D9. [测试·老版本] pre-1.8 服务器没有方块探针命令**
[实测：1.7.10/1.12.2] RCON 可用，但 `/testforblock` 1.8+ 才有、`/execute if block` 1.13+ 才有——1.7.10 无任何方块断言命令。替代：**解析存档 region 文件**（自研 Anvil/NBT 解析器，服务器写盘即服务器权威证据；注意 1.7.10 NBT 用数字物品 id：熔岩桶=327、冰=79）；1.12.2 有 `testforblock` 可用。bot 方面 mineflayer 最低支持 1.8.8——1.7.10 无 bot 路线，玩家相关行为只能"代码审查 + 存档证据"或如实标注未验证。1.12.2 无头服务器下界维度无玩家不加载——下界类断言在无 FML 客户端时天然受限，如实标注。

---

## 第二部分：案例研究（特定 mod 的特有机制，方法可复用，结论不可复用）

> 以下来自组织项目 handy-shulkers（手持潜影盒 / 手持床睡觉）的机制性 bug。这类问题只在"模拟原版方块行为"的功能里出现；读它的目的是学**排查套路**，不是背修复。

### 案例一：手持床原地睡觉（原版内部约束类 bug 的标准排查路径）

症状链：右键床没反应 → 瞬间醒 → "幽灵床" → 删床复制物品 → 睡姿固定一个方向。五个症状、一条根因链：

1. **没反应 / 幽灵床**：`ServerPlayer.startSleepInBed(pos)` 第一行无条件读 `getBlockState(pos).getValue(FACING)`，对空气调用直接抛异常——服务器报错，客户端已预测放置又回滚。
2. **瞬间醒**：`LivingEntity.tick` 每 tick 调 `checkBedExists()`（睡姿位置必须是 `BedBlock`），没有真床方块立刻强制醒来。
3. **复制物品**：清理临时方块用普通 `setBlock` 会掉落物品 → 一变二；用 `3 | Block.UPDATE_SUPPRESS_DROPS`。
4. **睡姿固定**：睡姿由**床方块的 `FACING` 属性**决定，不是实体朝向——放床时只设 `PART` 没设 `FACING` 就永远一个方向。

**方法论（可复用）**：功能"模仿原版行为"时，先反编译原版（sources jar，定位方法见 `api-verification.md` §3）回答三个问题：① 入口方法有哪些无条件前置读取（崩在何处）？② 运行期每 tick 校验什么（何时回退）？③ 行为的实际数据载体是实体还是方块状态（改哪里才生效）？然后设计"满足前置 → 建临时物 → tick 级清理器防残留防复制"三件套。

### 案例二：手持容器（组件存储类功能的数据安全链）

一个"以物品组件为存储的打开-编辑-写回"功能，必须同时做到：引用相等的 stillValid、容量上限拒绝、禁嵌套写入、拦截"把容器放进自己"、每变更即时写回（防崩溃丢数据，宁多序列化不冒险延迟写）。任何一环缺失都是静默数据损坏，且玩家报告时数据已经丢了——这类功能验收标准是"所有破坏路径都试一遍"。

### 案例三：同一逻辑 × N 构建的回归面（多构建项目通用）

项目的每个（版本 × Loader）构建都包含同一份核心逻辑时，任何行为修复要落到所有构建、在所有构建上验证。组织内实测项目 N=4（1.21.1/1.21.11 × Fabric/NeoForge）。省力规则：core 逐字节相同 + diff 即差异清单 + 版本坐标集中在 `gradle.properties` + tags 可选引用吃掉大部分 mod 兼容工作。漏改一处的典型症状是"某个平台独有 bug"——先 diff 各构建同源文件再查逻辑。
