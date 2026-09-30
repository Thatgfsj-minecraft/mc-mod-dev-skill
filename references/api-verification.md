# API 核实规程（不凭记忆猜 API）

`SKILL.md` §7 的执行手册。适用于任何版本、任何 Loader、任何映射：凡对 API 的存在性、签名、语义、生命周期有不确定，先核实再写。

## 1. 触发条件

任一成立即必须核实：

- 不确定类是否存在 / 属于哪个包 / 属于哪一侧（Server/Client）
- 不确定方法签名、参数含义、返回类型语义
- 不确定事件 / 回调的触发时机、触发线程、两侧行为
- 不确定注册机制（Registry / DeferredRegister / entrypoint / 注解扫描）或注册时机
- 不确定网络、命令、数据组件、NBT、配置等子系统 API
- 从记忆里"想起来"的写法在当前版本 / Loader / 映射下没有证据支持

## 2. 核实优先级

1. **项目现有代码**：grep 同类功能怎么写。已有 mod 的写法就是该项目环境的活文档。
2. **当前项目依赖**：`./gradlew -q dependencies`（或指定 configuration）确定 artifact 与版本，再到 Gradle 缓存找 jar。
3. **映射源码**：解包 sources jar 直接读（§3）。
4. **原版 / Loader 源码**：机制内部约束必须反编译确认——入口方法有哪些无条件前置读取、运行期每 tick 校验什么、行为数据实际存在实体还是方块状态上。
5. **官方或项目文档**：Loader 官方文档、映射查询工具（如 Linkie）、上游 GitHub 源码 / 补丁。
6. **最小编译探针**：仍不确定时写最小代码跑 `compileJava` 验证假设，再实现。

## 3. 反编译 / 读源码的具体手法

找 sources jar（不同构建系统的缓存位置不同，按名字搜最稳）：

```bash
find "$GRADLE_USER_HOME/caches" -name '*-sources.jar' 2>/dev/null | grep -iE 'minecraft|neoform|forge|fabric'
# Fabric Loom：GRADLE_USER_HOME/caches/fabric-loom/ 与项目 .gradle/loom-cache/
# ForgeGradle：GRADLE_USER_HOME/caches/forge_gradle/
# ModDevGradle：NeoForm Runtime 缓存（同样用上面的 find 定位）
```

读内容：

```bash
unzip -l  <jar> | grep -i '<ClassName>'        # 找类文件路径
unzip -p  <jar> net/minecraft/.../Foo.java     # 读单个类源码
javap -cp <jar> net.minecraft...Foo            # 无 sources 时看签名
```

Loom 项目还有更快的查名手法：映射产物生成时会落 `mappings.tiny`（如 `GRADLE_USER_HOME/caches/fabric-loom/<mc>/loom.mappings.*/mappings.tiny`），直接 `grep` 类名 / 方法名 / record 组件即可，且带**完整方法描述符与组件序号**——比解包映射 jar 或 javap 更快更准（[实测：1.21.11] 用它复核出 `SavedDataType` 真实包路径与构造器签名）。

缓存里没有 sources jar（fresh clone 首跑常见）就先生成：

- Fabric Loom：`./gradlew genSources`，生成后回到上面的 `find` 定位。
- ModDevGradle / ForgeGradle：先跑一次正常构建（NFRT / ForgeGradle 会产出反编译产物），再回到上面的 `find`；或用 IDE 打开项目让 IDE 拉取源码。

跨映射查名：Linkie（linkie.shedaniel.dev）支持指定 MC 版本下 Mojmap / Yarn / official 及老版本 MCP / SRG 命名空间互查。

## 4. 常见"记忆陷阱"清单（跨版本 / 跨 Loader 高发）

- **类名 / 包名漂移**：同类功能在 Mojmap 与 Yarn 下包路径不同；Mojmap 自己也会改名（已验证：1.21.11 线 `ResourceLocation` → `Identifier`，包仍在 `net.minecraft.resources`）。
- **语义级变化**：返回常量改名但旧名仍在，编译通过但行为错（已验证：1.21.2+ Mojmap 拆出 `InteractionResult.SUCCESS_SERVER`，纯服务端动作沿用旧常量会吞交互包）——这类只能靠行为测试抓。
- **Loader API 自己也在小版本间变**（已验证：Fabric API 的 `UseItemCallback` 返回类型在 1.21.1 → 1.21.11 变更）。
- **注册时机**：静态初始化 vs 构造期 vs 事件期，不同 Loader / 版本要求不同。
- **侧归属**：仅客户端类不能出现在两端共用的代码路径上（界面类、`ClientLevel` / `ServerLevel` 等）。
- **机制增删**：数据组件、维度环境属性等机制只在部分版本存在。

标注"已验证"的条目，证据见 `environments/` 下对应文件；其余条目为通用模式提醒。

## 5. 跨版本 / 迁移任务的核实流程

1. **diff 同源代码**：同一逻辑在两个版本 / Loader 构建间 diff，差异点即差异清单。
2. **编译两遍**：先旧环境编译通过，再搬新环境，把编译错误逐个归类；归类不了的当场反编译确认。
3. **行为测试兜底**：语义变化编译器抓不到，E2E 行为断言是唯一防线。
4. **沉淀**：验证过的新差异按 `references/README.md` 约定写入对应 `environments/` 文件。

## 6. 沉淀约定

- 结论必须带验证方式（哪个版本、哪个 Loader、怎么验证的）。
- 只写验证过的；推测标注"未验证"并尽快用编译 / 行为测试升级为已验证。
