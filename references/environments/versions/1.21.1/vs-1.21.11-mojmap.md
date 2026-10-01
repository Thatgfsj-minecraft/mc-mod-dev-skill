# Minecraft 1.21.1 ↔ 1.21.11 API 差异（Mojmap）

> **环境限定知识（已验证）**：两端验证于 **Minecraft 1.21.1 ↔ 1.21.11、Mojmap 映射**——同一份逻辑在 1.21.1 / 1.21.11 × Fabric / NeoForge 四个构建下编译并跑通行为测试。这不是默认目标环境，也不代表其他版本对之间存在相同差异；其他环境按 `../../../README.md` 的约定另立文件。
> **用作中间版本的候选清单**：迁移到 1.21.2–1.21.10 时，可把本表当候选差异清单，但每条只在其**适用边界**内成立（各节已标注边界；未标注 = 中间版本适用性未验证，必须按 `../../../api-verification.md` 逐条核实）。

跨这两个版本开发时需要逐条核对的 API 变化。

## 1. InteractionResult：服务端动作返回值

| 1.21.1 | 1.21.11 |
|---|---|
| `InteractionResult.SUCCESS` | `InteractionResult.SUCCESS_SERVER` |

1.21.2+ 把"纯服务端动作"（开菜单、睡觉等不需要客户端播动画的结果）拆出 `SUCCESS_SERVER`。**这个差异编译器抓不到**：用旧写法能编译通过，但客户端会按"客户端成功"处理，产生界面异常关闭、手臂不摆动等行为 bug——必须靠行为测试发现。

适用边界：`SUCCESS_SERVER` 自 **1.21.2+** 存在，对 1.21.2–1.21.11 的所有中间版本成立（1.21.4 / 1.21.8 / 1.21.9 均已探针实测成立）。

## 2. 资源定位类改名

- `net.minecraft.resources.ResourceLocation` → `net.minecraft.resources.Identifier`
- 所有 `ResourceLocation.fromNamespaceAndPath(ns, path)` → `Identifier.fromNamespaceAndPath(...)`
- 影响面：一切 `TagKey.create(...)`、注册名、网络 id 构造。
- 注意：包仍在 `net.minecraft.resources`——用包路径区分 Mojmap 的 `Identifier` 与 Yarn 的 `net.minecraft.util.Identifier`。

适用边界：改名在 **1.21.11** 完成，**1.21.10 及之前仍为 `ResourceLocation`**（据 NeoForge 官方升级 primer：1.21.11 primer 专节记载改名，1.21.9 primer 仍通篇 `ResourceLocation`）。动手前可用 sources jar 一键核实。
**[实测：mapped jar `unzip -l`，1.21.8 / 1.21.9 / 1.21.10 移植时核实]** 三版本的 mojmap jar 均只有 `resources/ResourceLocation.class`、无 `Identifier.class`（1.21.11 jar 只有 `Identifier.class`）——改名边界坐实在 1.21.10 → 1.21.11。

## 3. Container 开关回调：Player → ContainerUser

| 1.21.1 | 1.21.11 |
|---|---|
| `startOpen(Player player)` / `stopOpen(Player player)` | `startOpen(ContainerUser user)` / `stopOpen(ContainerUser user)` |

**1.21.9 引入** `ContainerUser` 抽象（容器可被非玩家实体打开；据 NeoForge 1.21.9 升级 primer——本仓库自身只验证了 1.21.1 = `Player`、1.21.11 = `ContainerUser` 两端；另实测 1.21.4 / 1.21.8 均无此类、1.21.9 有（探针 skill-practice/1.21.4-fabric、1.21.8-fabric 与 1.21.9-fabric），引入边界 1.21.9 实测成立）。需要拿实体时用 `user.getLivingEntity()`；自定义 `Container` 实现里播音效 / 做统计的代码要改签名，新增 import `net.minecraft.world.entity.ContainerUser` 与 `LivingEntity`。

## 4. 访问器风格：getMessage → message

`Player.BedSleepingProblem`（以及类似的旧式枚举 / 常量类）取文案从 `getMessage()` 改为 record 风格的 `message()`。

适用边界：中间版本未逐点验证。

## 5. 睡觉与维度 API

- 1.21.1 判定"该维度能否睡"：`DimensionType.bedWorks()`。1.21.11 移除，改为环境属性系统：
  ```java
  BedRule bedRule = level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos);
  bedRule.canSleep(level)   // 能否睡
  bedRule.asProblem()       // 不能睡时的 BedSleepingProblem
  ```
- 1.21.11 移除 `ServerLevel.isDay()`（改用时间 / 维度属性重算）。
- ~~1.21.11 `player.serverLevel()` 收回~~ **[实测修正：handyshulkers 1.21.4–1.21.10 移植，javap]** `ServerPlayer.serverLevel()` → 协变 `level()` 的真实边界是 **1.21.5 → 1.21.8**：1.21.4/1.21.5 有 `serverLevel()` 无协变 `level()`；1.21.8/1.21.9/1.21.10 相反（1.21.11 同）。
- `startSleepInBed` 返回值不再是 `Either<BedSleepingProblem, Unit>`，用 `var` 接。
- **两版本共同点**：`ServerPlayer.startSleepInBed(pos)` 会无条件读 `getBlockState(pos)` 的方块属性——对空气位置调用直接抛异常。任何"原地睡觉"类功能必须先让目标方块真实存在。

适用边界：`bedWorks()` → 环境属性的替换见 NeoForge 1.21.11 primer；`isDay()` 移除、`serverLevel()` 收回、`startSleepInBed` 返回值变化的中间版本适用性未逐点验证。最后一条"两版本共同点"为两端实测，中间版本大概率同样成立但仍须核实。
**[实测：javap，1.21.8 / 1.21.9 / 1.21.10 移植时核实]** 世界出生点 API 的切换边界在 **1.21.8 → 1.21.9**：1.21.8 仍只有 `ServerLevel.setDefaultSpawnPos(BlockPos, float)`（`MinecraftServer` 无 `setRespawnData`、无 `LevelData$RespawnData`）；1.21.9 / 1.21.10 均已有 `MinecraftServer.setRespawnData(LevelData.RespawnData.of(ResourceKey<Level>, BlockPos, float, float))`。

## 6. noise_router 密度字段改名（数据包 JSON）

自定义 `noise_settings` 的密度路由字段：**≤1.21.8 用 `initial_density_without_jaggedness`；1.21.9 起改名为 `preliminary_surface_level`**。字段用错时世界生成阶段直接解码失败（服务器日志报 codec 错误），启动冒烟即可裁决。

**[实测：javap NoiseRouter record 组件 + class 常量池字符串，1.21.8 / 1.21.9 / 1.21.10 移植时核实；两端为此前四构建实测]** 1.21.8 组件为 `initialDensityWithoutJaggedness()`；1.21.9 / 1.21.10 为 `preliminarySurfaceLevel()`（常量池含 `preliminary_surface_level`、不含旧名）。1.21.2–1.21.7 未逐点验证，按邻卡 + 核实流程处理。

## 7. SavedData 存储变体

| 1.21.1 | 1.21.8+（实测点） |
|---|---|
| `SavedData.Factory` + `computeIfAbsent(factory, id)` + 抽象 `save(CompoundTag, HolderLookup.Provider)` | `SavedDataType<T>`（id + supplier + Codec + DataFixTypes）+ `computeIfAbsent(savedDataType)`；基类不再有抽象 `save()`，`SavedData$Factory` 已不存在 |

**[实测：javap + 编译实证，1.21.4 / 1.21.5 / 1.21.8 / 1.21.9 / 1.21.10 / 1.21.11]** `saveddata/SavedDataType.class` 在 **1.21.5** 起即可用（record，4 参构造器 `(String, Supplier<T>, Codec<T>, DataFixTypes)` + `DimensionDataStorage.computeIfAbsent(SavedDataType<T>)`）；**1.21.4 无此类**（`SavedData$Factory` + 抽象 `save` 仍在，1.21.1 式可用）。**引入边界 = 1.21.5**（修正"公开资料称 1.21.6"的先验）。1.21.8 的 `SavedData` 基类只剩 `setDirty/isDirty`（1.21.5/1.21.9/1.21.10 的基类形态未单独 javap，但 Factory 式在 1.21.5 已无法使用）。
**[实测：编译错误暴露，1.21.8]** `CompoundTag.getBoolean(String)` 在 1.21.8 返回 `Optional<Boolean>`（1.21.1 返回 `boolean`）——迁移布尔标记类代码时注意，候选清单此前未收录。

## 排查方法论

本表的沉淀过程（可复用到其他版本对）：diff 同源代码 → 编译两遍归类错误 → 行为测试兜底语义变化。完整流程见 `../../../api-verification.md` §5。
