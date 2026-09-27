# 1.21.1 ↔ 1.21.11 Mojmap API 差异对照表

证据来源：handy-shulkers 仓库 `diff 1.21.1/*/src/main/java/dev/handyshulkers/X.java 1.21.11/*/src/main/java/dev/handyshulkers/X.java`。15 个 core 文件中 11 个两版本逐字节相同，差异集中在 4 个文件：`HandItemUse.java`、`HandyShulkers.java`、`ItemStackContainer.java`、`ShulkerOpenLogic.java`。**结论：两版本间差异是点状的、可枚举的，维护方式是"diff 四个项目找差异点"。**

## HandItemUse.java（交互分发）

| 1.21.1 | 1.21.11 | 原因 |
|---|---|---|
| `import com.mojang.datafixers.util.Either;` | 删除 | `startSleepInBed` 返回类型不再是 `Either`，用 `var` 接 |
| `return InteractionResult.SUCCESS;`（服务端动作处） | `return InteractionResult.SUCCESS_SERVER;` | 1.21.2+ 拆分出 `SUCCESS_SERVER`：纯服务端动作必须用它，否则客户端收到"成功"却无对应行为 |
| `ServerLevel level = player.serverLevel();` | `ServerLevel level = player.level();` | `ServerPlayer.level()` 协变特化为 ServerLevel，`serverLevel()` 收回 |
| `Component message = problem.getMessage();` | `problem.message();` | `Player.BedSleepingProblem` 访问器改名 |

## HandyShulkers.java（资源定位）

- `net.minecraft.resources.ResourceLocation` → `net.minecraft.resources.Identifier`
- 全部 `ResourceLocation.fromNamespaceAndPath(MOD_ID, "x")` → `Identifier.fromNamespaceAndPath(...)`
- 影响：所有 `TagKey.create(Registries.ITEM, ...)` 定义处。

## ItemStackContainer.java（Container 接口签名）

| 1.21.1 | 1.21.11 |
|---|---|
| `startOpen(Player player)` / `stopOpen(Player player)` | `startOpen(ContainerUser user)` / `stopOpen(ContainerUser user)` |
| `playSound(level, player, sound)`（内部取 player 坐标） | `playSound(level, x, y, z, sound)`（调用方先 `user.getLivingEntity()` 取坐标） |

1.21.11 引入 `ContainerUser` 抽象（容器可被非玩家实体打开），需要 `import net.minecraft.world.entity.ContainerUser` 与 `LivingEntity`。

## ShulkerOpenLogic.java

- 服务端动作返回值同 HandItemUse：`SUCCESS` → `SUCCESS_SERVER`。

## 睡觉相关（手持床原地睡场景）

- 1.21.1：`DimensionType.bedWorks()` 判定维度能否睡（配合 `level.isDay()`、`Monster.isPreventingPlayerRest(player)` 等由 `startSleepInBed` 内部处理）。
- 1.21.11：`bedWorks()` 移除，改为环境属性系统：
  ```java
  BedRule bedRule = level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos);
  bedRule.canSleep(level)   // 能否睡
  bedRule.asProblem()       // 不能睡时的 BedSleepingProblem
  ```
- 1.21.11 `ServerLevel.isDay()` 被移除（用时间/维度属性重算）。
- `ServerPlayer.startSleepInBed` 两版本都会**无条件读** `getBlockState(pos).getValue(HorizontalDirectionalBlock.FACING)`——对空气调用直接崩，床必须先落地（1.21.11 崩点变为 `BedRule` 查询依赖方块存在与否，同样先放床）。

## 排查方法论

1. 同名 core 文件 diff：`diff 1.21.1/<loader>/src/.../X.java 1.21.11/<loader>/src/.../X.java`。
2. 编译期暴露：先改 1.21.1 编译过，再改 1.21.11，把编译错误逐个对照上表归类。
3. 运行期语义差异（如 `SUCCESS_SERVER`）编译器抓不到，靠 E2E 行为测试抓（界面闪关、手臂不摆动等）。
