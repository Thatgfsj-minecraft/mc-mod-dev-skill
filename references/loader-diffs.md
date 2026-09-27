# Fabric ↔ NeoForge 加载器差异（1.21.x）

双加载器开发的标准对照表。推荐的项目结构：core 类不 import 任何加载器类，全部加载器差异收敛到唯一入口类——这样 core 可以在四个项目（2 版本 × 2 加载器）间逐字节保持一致，加载器升级或版本迁移时只动入口。

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
| 插件 | `id 'fabric-loom' version '1.17.21'`（1.21.x 钉死，见 SKILL.md 环境节） | `id 'java-library'` + `id 'net.neoforged.moddev' version '2.0.147'` |
| 依赖 | `minecraft "com.mojang:minecraft:..."`、`loom.officialMojangMappings()`、fabric-loader、fabric-api | `neoForge { version = "${project.neoforge_version}" }` |
| 插件仓库 | settings.gradle pluginManagement：Fabric maven + mavenCentral + gradlePluginPortal | 另加 `https://maven.neoforged.net/releases` |
| 元数据 | `fabric.mod.json`（entrypoints.main、`environment: "*"`、depends 声明 MC 版本范围） | `META-INF/neoforge.mods.toml`（`modLoader="javafml"`、`loaderVersion="[4,)"`、依赖 `side="BOTH"`） |
| version 展开 | processResources 展开 `fabric.mod.json` | processResources 展开 `META-INF/neoforge.mods.toml` |
| 预置运行 | 无（用外部测试服） | `neoForge.runs { client{} server{} }`（行为验证仍建议外部专用服） |

## 语义差异提醒

- NeoForge 取消事件必须同时 `setCancellationResult`，否则两侧玩家侧交互结果不一致。
- Fabric 回调签名在小版本间会变（UseItemCallback 返回类型 1.21.1→1.21.11 变更），NeoForge 事件 API 跨这两个版本稳定——**迁移版本时优先编译 NeoForge 侧确认 core 无恙，再处理 Fabric 侧签名**。
- 测试服安装器装出的 fabric-loader 版本可能比构建期依赖新（如 0.19.5 vs 0.19.3），属正常，依赖范围声明写宽松些（`>=0.16.0`）。
