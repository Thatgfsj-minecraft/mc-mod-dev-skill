# 1.21.1 ↔ 1.21.11 Mojmap API 差异对照

跨这两个版本开发时需要逐条核对的 API 变化。整理自实战项目四个构建（1.21.1/1.21.11 × Fabric/NeoForge）的同源代码 diff，验证方式：同一份逻辑在两版本下编译并跑通行为测试。

## 1. InteractionResult：服务端动作返回值

| 1.21.1 | 1.21.11 |
|---|---|
| `InteractionResult.SUCCESS` | `InteractionResult.SUCCESS_SERVER` |

1.21.2+ 把"纯服务端动作"（开菜单、睡觉等不需要客户端播动画的结果）拆出 `SUCCESS_SERVER`。**这个差异编译器抓不到**：用旧写法能编译通过，但客户端会按"客户端成功"处理，产生界面异常关闭、手臂不摆动等行为 bug——必须靠行为测试发现。

## 2. 资源定位类改名

- `net.minecraft.resources.ResourceLocation` → `net.minecraft.resources.Identifier`
- 所有 `ResourceLocation.fromNamespaceAndPath(ns, path)` → `Identifier.fromNamespaceAndPath(...)`
- 影响面：一切 `TagKey.create(...)`、注册名、网络 id 构造。

## 3. Container 开关回调：Player → ContainerUser

| 1.21.1 | 1.21.11 |
|---|---|
| `startOpen(Player player)` / `stopOpen(Player player)` | `startOpen(ContainerUser user)` / `stopOpen(ContainerUser user)` |

1.21.11 引入 `ContainerUser` 抽象（容器可被非玩家实体打开）。需要拿实体时用 `user.getLivingEntity()`；自定义 `Container` 实现里播音效/做统计的代码要改签名，新增 import `net.minecraft.world.entity.ContainerUser` 与 `LivingEntity`。

## 4. 访问器风格：getMessage → message

`Player.BedSleepingProblem`（以及类似的旧式枚举/常量类）取文案从 `getMessage()` 改为 record 风格的 `message()`。

## 5. 睡觉与维度 API

- 1.21.1 判定"该维度能否睡"：`DimensionType.bedWorks()`。1.21.11 移除，改为环境属性系统：
  ```java
  BedRule bedRule = level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos);
  bedRule.canSleep(level)   // 能否睡
  bedRule.asProblem()       // 不能睡时的 BedSleepingProblem
  ```
- 1.21.11 移除 `ServerLevel.isDay()`（改用时间/维度属性重算）。
- 1.21.11 `player.serverLevel()` 收回：`ServerPlayer.level()` 协变特化为 `ServerLevel`，直接用 `player.level()`。
- `startSleepInBed` 返回值不再是 `Either<BedSleepingProblem, Unit>`，用 `var` 接。
- **两版本共同点**：`ServerPlayer.startSleepInBed(pos)` 会无条件读 `getBlockState(pos)` 的方块属性——对空气位置调用直接抛异常。任何"原地睡觉"类功能必须先让目标方块真实存在。

## 排查方法论

1. **diff 同源代码**：把同一逻辑文件在两个版本项目间 diff，差异点即版本 API 差异清单。
2. **编译两遍**：先在 1.21.1 编译通过，再搬 1.21.11，把编译错误逐个归类到本表；归类不了的当场反编译确认（loom-cache 的 `*-sources.jar`）。
3. **行为测试兜底**：语义变化（如 `SUCCESS_SERVER`）编译器抓不到，E2E 行为断言是唯一防线——这正是双版本都要跑 E2E 的原因。
