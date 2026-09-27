# 踩坑手册：症状 → 根因 → 修复

全部来自 handy-shulkers 实际发生过的 bug，每条都有源码级根因。

## A. 构建环境

### A1. Loom 1.18+ 报 "requires JVM 25"
- 症状：升级 fabric-loom 后 Gradle 起不来。
- 根因：Loom 1.18 用 Java 25 FFM API 重写了 native 库，Gradle JVM 必须 25+。
- 修复：做 MC 1.21.x 就钉 `fabric-loom 1.17.21` + Gradle 9.5.1 + JDK 21。

### A2. NeoForge 依赖解析失败
- 症状：`net.neoforged.moddev` 插件或 neoform-runtime 下载不到。
- 根因：机器全局 `~/.gradle/init.gradle` 强制阿里云镜像，镜像上没有 NeoForge 生态的构件。
- 修复：`GRADLE_USER_HOME=/干净目录 ./gradlew build`（目录里不能有 init.gradle）。

### A3. wrapper 首跑卡住/超时
- 症状：隔离 GRADLE_USER_HOME 后 services.gradle.org 下载超时。
- 修复：从 `~/.gradle/wrapper/dists/` 预拷 `gradle-9.5.1-bin` 到隔离目录同名路径。

### A4. 推不了 `.github/workflows/`
- 根因：OAuth token 缺 `workflow` scope。
- 修复：CI 文件先放 `ci/build.yml`；用户执行 `gh auth refresh -h github.com -s workflow` 后再移动提交。
- 另：upload-artifact v4 **artifact 名不允许 `/`**——matrix 里 project 路径和 artifact 名要用两条平行数组。

## B. 交互模型（服务端权威）

### B1. 界面"闪一下就关"
- 根因（两个叠加）：① 客户端伪造 `SUCCESS` 吞掉 use 包；② 便携菜单用原版 `stillValid(access)`，它每 tick 检查锚点方块是不是对应方块，玩家脚下不是工作台 → 立即关闭。
- 修复：客户端一律 `PASS`；子类菜单覆写 `stillValid(p) { return p.isAlive(); }`。

### B2. 对着有自己界面的方块右键行为冲突
- 规则：`level.getBlockState(pos).getMenuProvider(level, pos) != null` 就让路（原版行为优先），只在瞄准无菜单方块或空气时接管手持物品。

### B3. 潜行语义
- 用 XOR 门：`if (config.requireSneak != player.isShiftKeyDown()) return PASS;`——默认"不潜行触发、潜行放置"，配置反转，永不出现"两种都触发/都不触发"。

### B4. 假玩家（自动化）误触
- `player.getClass() != ServerPlayer.class` 判定假玩家，配置开关 `allowFakePlayers` 默认 false；用 `getClass()` 精确比较而不是 `instanceof`（防子类绕过）。

## C. 手持容器（数据安全）

### C1. 相同 NBT 的两个盒子改错堆栈
- 根因：`Inventory.contains` 用**组件相等**判断，两个内容一样的盒子会互相"顶替"。
- 修复：`stillValid` 里用**引用相等**（`inventory.getItem(i) == box`）逐槽扫描。

### C2. 大盒子静默丢数据
- 根因：菜单最多 6 行 54 格，超容量盒子的第 55 格起在首次写回时被静默丢弃。
- 修复：打开前容量检查（EntityBlock 无头构造探针 + CONTAINER 组件计数），超限拒绝 + action bar 提示（en_us/zh_cn 双语）。

### C3. 盒子套盒子数据损坏
- 修复：菜单槽位 `mayPlace` 拒绝带 `DataComponents.CONTAINER` 的物品；`clicked()` 拦住"把被打开的盒子放进自己"。

### C4. 配置项 true 静默变 false
- 根因：Gson 反序列化不跑构造器，`fromJson` 后缺失键的字段保持默认构造值——若默认值来自字段初始化器而 JSON 没有该键，行为不变；但把 `fromJson` 结果直接当实例用时，任何构造器逻辑（归一化、校验）都被跳过。
- 修复：**逐字段手写 JSON 解析 + 显式默认值 + 越界归一化**（如 forceRows 不在 1..6 就回 -1）。

## D. 手持床原地睡觉

### D1. 右键床"没反应"/瞬间醒
- 根因：`LivingEntity.tick` 每 tick `checkBedExists()`（`getBlockState(sleepingPos).getBlock() instanceof BedBlock`），床上没真床方块立刻 `stopSleeping()`。
- 修复：先 `setBlock` 放 FOOT+HEAD 两半真床，再 `startSleepInBed(foot)`；`TempBedTracker`（ConcurrentHashMap<UUID, 入口维度+两坐标>）在服务端 END_SERVER_TICK 里检查玩家醒来/下线就删床。

### D2. "幽灵床"（客户端先显示放置又消失）
- 根因：`ServerPlayer.startSleepInBed` 第一行 `getBlockState(pos).getValue(FACING)`，对空气调用直接异常，服务器报错但客户端已预测放置。
- 修复：同 D1（床先落地），失败路径立即删床并向玩家发 `BedSleepingProblem` 原版文案。

### D3. 床被复制
- 根因：删床用普通 setBlock 会掉落床物品，玩家手里还拿着床 → 一变二。
- 修复：删床 flag 用 `3 | Block.UPDATE_SUPPRESS_DROPS`。

### D4. 睡姿永远朝一个方向
- 根因：放床只 `setValue(PART, ...)` 没设 `FACING`，默认朝北；玩家躺向由床的 FACING 决定。
- 修复：`bed.setValue(BedBlock.FACING, player.getDirection())`，FOOT 在玩家脚下、HEAD 在视线前方一格；用 `state.hasProperty(BedBlock.FACING)` 兜底无原版属性的 modded 伪床。
- 教训：**睡姿/朝向由方块状态决定，不是玩家实体朝向**；验收要用 E2E 对四个方向逐一断言方块状态。

### D5. 隐藏行为：睡觉会设重生点
- `startSleepInBed` 内部调 `setRespawnPosition`——临时床删掉后重生点指向空气。原版会回退世界出生点并提示，可接受；若产品不接受需在醒来时重置。

## E. E2E 测试

### E1. mineflayer 1.21.x 客户端解析崩溃
- 症状：`PartialReadError: ... ArmorTrimMaterial`，机器人状态错乱。
- 根因：minecraft-data 的 1.21.x protocol 定义与服务器实际物品组件不同步。
- 修复：**架构上放弃客户端断言**——mineflayer 只做 join/look/activateItem/activateBlock，所有断言走 RCON（`/data get entity`、`/execute if block`）；bot 协议钉 `version: '1.21.1'`。服务器日志零异常即服侧无 bug。

### E2. RCON `run say` 探针永远"失败"
- 症状：`execute if block ... run say MATCHED` 通过 RCON 拿到的响应永远是空串，断言全挂（且**连真实成功也判负**）。
- 根因：`say` 输出走聊天广播，不回传 RCON 通道。
- 修复：用裸 `execute if block <pos> <方块>[状态]`，响应是内联的 `Test passed` / `Test failed`；布尔断言直接匹配这两个词。
- 经验：**测试工具自身的可信度要先于被测代码验证**——先对已知方块（如 0 -64 0 的 bedrock）跑一轮探针自检。

### E3. 测试服搭建要点
- `online-mode=false`（离线 bot 可进）、`enable-rcon=true` + 密码/端口、`level-type=minecraft\:flat`（坐标可预测）、`spawn-protection=0`、`difficulty` 测睡觉时切 peaceful 防怪物拦截、`eula=true`。
- 多套服务器并行时 RCON 端口错开（25575/25576）。
