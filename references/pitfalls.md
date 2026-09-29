# 踩坑手册

分两部分：

- **第一部分：通用坑**——条目标注了适用范围（如 `[Fabric Loom]`、`[1.21.x]`）的只在该范围内成立；未标注范围为跨环境成立。涉及原版机制的类名为 Mojmap 写法，其他映射下名称不同、概念一致。
- **第二部分：案例研究**——来自组织实战项目的特有机制 bug，**结论不可复用到别的 mod**；保留价值在于示范排查方法。

---

## 第一部分：通用坑

### 构建环境

**A1. [构建系统：Fabric Loom × 1.21.x] Loom 1.18+ 报 "requires JVM 25"**
升级 fabric-loom 后 Gradle 起不来。Loom 1.18 用 Java 25 FFM API 重写了 native 层，Gradle JVM 必须 25+。做 MC 1.21.x 目标时钉 `fabric-loom 1.17.21` + Gradle 9.5.1 + JDK 21（其他版本线不受此组合约束，但升级构建插件前先核对其 JVM 要求）。

**A2. [Gradle 通用] 全局 init 脚本 / 镜像弄坏插件解析**
机器全局 `~/.gradle/init.gradle` 强制镜像（如阿里云）时，Loader 插件 / 依赖在镜像上可能不存在。修复：`GRADLE_USER_HOME=/干净目录 ./gradlew build`（目录里不能有 init.gradle）。

**A3. [Gradle 通用] wrapper 首跑下载超时**
隔离 GRADLE_USER_HOME 后 services.gradle.org 下载超时。修复：从 `~/.gradle/wrapper/dists/` 预拷对应发行包（如 `gradle-9.5.1-bin`）到隔离目录同名路径。

**A4. [CI 通用] 两连坑**
① 推 `.github/workflows/` 需要 token 有 `workflow` scope（`gh auth refresh -h github.com -s workflow`），否则先放 `ci/` 目录；② upload-artifact v4 的 **artifact 名不允许 `/`**——matrix 里项目路径和 artifact 名用两条平行数组。

### 交互模型

**B1. [通用] 客户端伪造成功会吞掉后续交互**
客户端事件处理器返回"成功"（如 Fabric 回调返回 `SUCCESS`、NeoForge 客户端侧取消并给结果）会让客户端认为已完成、不再发 use 包到服务端，表现为"界面闪一下就关""功能时灵时不灵"。规则：客户端一律放行（Fabric 返回 `PASS`、NeoForge 不取消），所有真实动作在服务端做。

**B2. [通用·原版容器机制] 没有方块锚点的菜单闪关**
从物品 / 远程打开任何菜单（便携工作台、随身容器类功能），原版 `stillValid(ContainerLevelAccess)` 每 tick 检查锚点方块类型，玩家脚下不是对应方块就立即关闭。子类覆写 `stillValid(p) { return p.isAlive(); }`。

**B3. [通用] 与原版方块行为冲突**
接管右键前先让路：`level.getBlockState(pos).getMenuProvider(level, pos) != null` 说明方块有自己的界面，保持原版行为。

**B4. [通用] 假玩家（自动化）误触**
判定 fake player 用 `player.getClass() != ServerPlayer.class`（精确比较，防子类绕过），并给配置开关；别用 `instanceof`。

**B5. [通用] 潜行语义组合**
用 XOR 门表达"默认不潜行触发、潜行放置；配置反转"：`if (config.requireSneak != player.isShiftKeyDown()) return PASS;`——永不出现两种都触发 / 都不触发。

### 数据安全

**C1. [通用] 配置项静默复位**
依赖 Gson 自动反序列化时构造器不运行，缺键 / 旧文件会让字段回到非预期值。逐字段手写解析 + 显式默认值 + 越界归一化（如 `forceRows` 不在 1..6 就回 -1）。

**C2. [通用] 引用相等 vs 组件相等**
以物品组件为存储的容器，用 `Inventory.contains` 之类做有效性判断会被"内容相同的另一个物品"顶替——有效性判断用**引用相等**逐槽扫描。

**C3. [通用] 容量上限静默丢数据**
任何"写入有硬上限的存储"的功能，打开前必须检查容量（用 EntityBlock 探针或组件计数），超限拒绝并提示（en_us / zh_cn 双语），绝不能静默截断。

### 测试

**D1. [测试·版本相关] mineflayer 客户端解析崩溃（1.21.x 实测）**
症状：`PartialReadError`（ArmorTrimMaterial 等），机器人状态错乱。根因：minecraft-data 的协议定义与服务器实际物品组件不同步。修复：架构上放弃客户端断言——机器人只做 join / look / 点击，所有断言走 RCON；bot 协议钉具体版本号。服务器日志零异常 = 服侧无 bug 的必要条件。（该缺陷在 1.21.x 实测出现；"断言只走服务端"的架构原则跨环境成立。）

**D2. [测试·RCON 通用] RCON `run say` 探针永远"失败"**
`execute if block ... run say MATCHED` 的 say 输出走聊天广播，**RCON 响应为空串**——无论条件真假，断言恒假。用裸 `execute if block <pos> <方块>[<状态>]`，响应是内联 `Test passed` / `Test failed`。
经验：**先验证测试工具自身**——写任何方块断言前对已知方块（如超平坦 0 -64 0 的 bedrock）跑探针自检。

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
