# Fabric ↔ NeoForge 加载器差异（1.21.x 实测）

> **环境限定知识（已验证）**：只覆盖 **Fabric ↔ NeoForge、Minecraft 1.21.x、Mojmap**。Forge / Quilt / 其他版本线不适用，条目不要外推到其他环境。
> 验证方式：同源 core 在 1.21.1 / 1.21.11 × 两 Loader 共四个构建下编译 + 行为测试。

双加载器开发的标准对照表。通用原则：core 类不 import 任何加载器类，加载器差异收敛到入口层——这样 core 可以在所有构建间保持一致，加载器升级或版本迁移时只动入口。

## 入口与事件注册

| 关注点 | Fabric | NeoForge |
|---|---|---|
| 入口声明 | `public class X implements ModInitializer` + `onInitialize()` | `@Mod(MOD_ID)` 类 + 构造函数 |
| 右键方块 | `UseBlockCallback.EVENT.register((player, level, hand, hit) -> InteractionResult)`；不处理返回 `InteractionResult.PASS` | `@SubscribeEvent static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e)`；不处理直接 return；**处理了才** `e.setCanceled(true); e.setCancellationResult(result);` |
| 右键物品 | `UseItemCallback.EVENT.register`，返回 `InteractionResultHolder<ItemStack>`（1.21.1）；**1.21.11 改为返回 `InteractionResult`**（Fabric API 自己改了签名） | `@SubscribeEvent PlayerInteractEvent.RightClickItem` |
| 服务端每 tick | `ServerTickEvents.END_SERVER_TICK.register(Foo::tick)` | `NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> Foo.tick(e.getServer()))` |
| 配置目录 | `FabricLoader.getInstance().getConfigDir().resolve("modid.json")` | `FMLPaths.CONFIGDIR.get().resolve("modid.json")` |
| 事件总线 | 每个 `XxxCallback.EVENT` 静态注册 | `NeoForge.EVENT_BUS.register(Class)` 类级 + `@SubscribeEvent` 方法级 |

## 构建脚本

| | Fabric | NeoForge |
|---|---|---|
| 插件 | `id 'fabric-loom' version '1.17.21'`（1.21.x 钉死，见 [pitfalls A1](../../pitfalls.md)） | `id 'java-library'` + `id 'net.neoforged.moddev' version '2.0.147'` |
| 依赖 | `minecraft "com.mojang:minecraft:..."`、`loom.officialMojangMappings()`、fabric-loader、fabric-api | `neoForge { version = "${project.neoforge_version}" }` |
| 插件仓库 | settings.gradle pluginManagement：Fabric maven + mavenCentral + gradlePluginPortal | 另加 `https://maven.neoforged.net/releases` |
| 元数据 | `fabric.mod.json`（entrypoints.main、`environment: "*"`、depends 声明 MC 版本范围） | `META-INF/neoforge.mods.toml`（`modLoader="javafml"`、`loaderVersion="[4,)"`、依赖 `side="BOTH"`） |
| version 展开 | processResources 展开 `fabric.mod.json` | processResources 展开 `META-INF/neoforge.mods.toml` |
| 预置运行 | 无（用外部测试服） | `neoForge.runs { client{} server{} }`（行为验证仍建议外部专用服） |

## 语义差异提醒

- NeoForge 取消事件必须同时 `setCancellationResult`，否则两侧玩家侧交互结果不一致。
- Fabric 回调签名在小版本间会变（UseItemCallback 返回类型 1.21.1→1.21.11 变更），NeoForge 事件 API 跨这两个版本稳定——**迁移版本时优先编译 NeoForge 侧确认 core 无恙，再处理 Fabric 侧签名**。
- 测试服安装器装出的 fabric-loader 版本可能比构建期依赖新（如 0.19.5 vs 0.19.3），属正常，依赖范围声明写宽松些（`>=0.16.0`）。

## 已验证版本坐标（1.21.x）

| | 1.21.1 | 1.21.11 |
|---|---|---|
| fabric-api | `0.116.17+1.21.1` | `0.141.6+1.21.11` |
| NeoForge | `21.1.252` | `21.11.45` |
| fabric-loader（构建期） | `0.19.3` | `0.19.3` |
| 依赖范围声明 | `>=1.21.1 <1.21.2` | `>=1.21.11 <1.21.12` |

## 配套实测架构：复制式四子项目 core

组织内验证过的一种多版本 × 多 Loader 布局（对应 `SKILL.md` §8 模式 4，**不是通用推荐**，其他项目按自身架构走）：

```
repo/
  1.21.1/fabric/     1.21.1/neoforge/
  1.21.11/fabric/    1.21.11/neoforge/     ← 每个都是完整独立 Gradle 项目
      src/main/java/<pkg>/            ← core 类四个项目保持一致（刻意不抽公共库）
      src/main/java/<pkg>/fabric/     ← 仅 Fabric 入口类
      src/main/java/<pkg>/neoforge/   ← 仅 NeoForge 入口类
      src/main/resources/
          fabric.mod.json | META-INF/neoforge.mods.toml
          assets/<id>/lang/{en_us,zh_cn}.json
          data/<id>/tags/item/*.json
```

- **为什么不抽公共库**：四份 core 可独立编译、独立审查，`diff` 两个版本目录直接得到版本差异清单；公共库引入多项目构建与 remap 顺序问题，收益配不上复杂度。
- 版本号在各子项目 `build.gradle` 的 `version = 'x.y.z'`，`processResources` 展开进 metadata——发版要改四处。
- 需要 mod 兼容时，tags 用**可选引用**：`{ "id": "#c:<类别>", "required": false }`——任何模组物品打上 `#c:` 标签即自动获得支持，目标环境没有该标签也不报错；建议覆盖全部原版变体（颜色、损坏阶段等），别只写一个默认变体。
