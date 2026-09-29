# Minecraft 1.21.1 ↔ 1.21.11 API 差异（Mojmap）

> **环境限定知识（已验证）**：只适用于 **Minecraft 1.21.1 ↔ 1.21.11、Mojmap 映射**的项目迁移与对照。这不是默认目标环境，也不代表其他版本对之间存在相同差异；其他环境按 `../../../README.md` 的约定另立文件。
> 验证方式：同一份逻辑在 1.21.1 / 1.21.11 × Fabric / NeoForge 四个构建下编译并跑通行为测试。

跨这两个版本开发时需要逐条核对的 API 变化。改名的确切中间版本（1.21.2–1.21.10 之间哪一个小版本发生）未逐点验证，迁移到中间版本时按 `../../../api-verification.md` 逐条核实。

## 1. InteractionResult：服务端动作返回值

| 1.21.1 | 1.21.11 |
|---|---|
| `InteractionResult.SUCCESS` | `InteractionResult.SUCCESS_SERVER` |

1.21.2+ 把"纯服务端动作"（开菜单、睡觉等不需要客户端播动画的结果）拆出 `SUCCESS_SERVER`。**这个差异编译器抓不到**：用旧写法能编译通过，但客户端会按"客户端成功"处理，产生界面异常关闭、手臂不摆动等行为 bug——必须靠行为测试发现。

## 2. 资源定位类改名

- `net.minecraft.resources.ResourceLocation` → `net.minecraft.resources.Identifier`
- 所有 `ResourceLocation.fromNamespaceAndPath(ns, path)` → `Identifier.fromNamespaceAndPath(...)`
- 影响面：一切 `TagKey.create(...)`、注册名、网络 id 构造。
- 注意：包仍在 `net.minecraft.resources`——用包路径区分 Mojmap 的 `Identifier` 与 Yarn 的 `net.minecraft.util.Identifier`。

## 3. Container 开关回调：Player → ContainerUser

| 1.21.1 | 1.21.11 |
|---|---|
| `startOpen(Player player)` / `stopOpen(Player player)` | `startOpen(ContainerUser user)` / `stopOpen(ContainerUser user)` |

1.21.11 引入 `ContainerUser` 抽象（容器可被非玩家实体打开）。需要拿实体时用 `user.getLivingEntity()`；自定义 `Container` 实现里播音效 / 做统计的代码要改签名，新增 import `net.minecraft.world.entity.ContainerUser` 与 `LivingEntity`。

## 4. 访问器风格：getMessage → message

`Player.BedSleepingProblem`（以及类似的旧式枚举 / 常量类）取文案从 `getMessage()` 改为 record 风格的 `message()`。

## 5. 睡觉与维度 API

- 1.21.1 判定"该维度能否睡"：`DimensionType.bedWorks()`。1.21.11 移除，改为环境属性系统：
  ```java
  BedRule bedRule = level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos);
  bedRule.canSleep(level)   // 能否睡
  bedRule.asProblem()       // 不能睡时的 BedSleepingProblem
  ```
- 1.21.11 移除 `ServerLevel.isDay()`（改用时间 / 维度属性重算）。
- 1.21.11 `player.serverLevel()` 收回：`ServerPlayer.level()` 协变特化为 `ServerLevel`，直接用 `player.level()`。
- `startSleepInBed` 返回值不再是 `Either<BedSleepingProblem, Unit>`，用 `var` 接。
- **两版本共同点**：`ServerPlayer.startSleepInBed(pos)` 会无条件读 `getBlockState(pos)` 的方块属性——对空气位置调用直接抛异常。任何"原地睡觉"类功能必须先让目标方块真实存在。

## 排查方法论

本表的沉淀过程（可复用到其他版本对）：diff 同源代码 → 编译两遍归类错误 → 行为测试兜底语义变化。完整流程见 `../../../api-verification.md` §5。
