# References：索引与加载策略

## 两层知识模型

```
第一层 通用规则（环境无关，默认可读）
  ../SKILL.md                  操作手册（怎么发现、怎么判断、怎么开发、怎么验证）
  environment-discovery.md     Phase 0 侦察手册
  api-verification.md          API 核实规程
  pitfalls.md 第一部分         通用坑（个别条目标注适用范围）
  e2e-testing.md               E2E 方法论（服务器安装随 Loader、断言跨环境）

第二层 环境知识（environments/，识别环境后按需加载）
  environments/
    versions/                  按 Minecraft 版本号分文件夹：versions/<版本>/README.md 版本卡
                               ＋ versions/<版本>/vs-*.md 已验证版本对差异
    loaders/                   按 Loader（对）× 版本线组织
    mappings/                  （预留）按映射体系组织
    build-systems/             （预留）按构建系统组织
```

## 加载规则

1. **默认可读**：第一层全部。
2. **Phase 0 完成后**：只读与目标环境匹配的环境知识——版本进 `versions/<版本号>/`，Loader 进 `loaders/`。**不通读所有环境文件**。
3. **迁移 / 跨版本对比任务**：才加载另一个版本 / Loader 的文件做对照（版本对差异统一放在较旧版本的文件夹下）。**迁移目标落在两个已验证端点之间时**，读取夹持的版本对差异作候选清单，逐条核实适用边界。
4. **没有匹配文件**：不猜。读同发布线最近邻版本的版本卡（见 versions/README.md 缺号说明）；连邻卡也没有就走 `api-verification.md` 核实流程；完成后按下面"新增环境知识"沉淀。
5. **可信级别**：版本卡条目按标签分级（无标签 = 公开常识；`[公开文档：…]` / `[实测：…]` / `[未核实]` 见标签含义），未标注"实测"的内容不能当验证结论用。

## 目录与命名约定

- `environments/versions/<mc版本>/README.md` —— 版本卡（Java / 发布线 / Loader / 映射 / 构建系统 / 时代特征 / 已验证经验索引）
- `environments/versions/<旧版本>/vs-<新版本>-<映射>.md` —— 版本对差异，放较旧版本文件夹下，各条目标适用边界
- `environments/loaders/<loaderA>-vs-<loaderB>-<版本线>.md`，如 `fabric-vs-neoforge-1.21.x.md`
- `environments/mappings/<映射>.md`、`environments/build-systems/<构建系统>.md` —— 未来按需
- 文件夹 / slug 一律用真实版本号（含 26.x 起的日期式版本线）与小写连字符，**精确匹配、禁止前缀匹配**；环境知识文件开头用引用块声明**适用环境**与**验证方式**。

## 新增环境知识（扩展方式）

1. 用通用规程（api-verification + e2e）在真实项目上完成一次验证（编译过 + 行为测试过）。
2. 版本经验 → 新建 / 更新 `versions/<版本>/`；Loader 经验 → `loaders/`；写明验证方式与适用边界。
3. 更新 `versions/README.md` 覆盖表（**版本覆盖的唯一事实来源**）；已验证经验另更新本 README 与根 README 的已验证覆盖表。
4. **不需要修改 SKILL.md**——新增版本 / Loader 不应触碰核心行为。

## 当前已验证覆盖

| 类别 | 文件 | 环境 |
|---|---|---|
| versions | `environments/versions/1.21.1/vs-1.21.11-mojmap.md` | MC 1.21.1 ↔ 1.21.11，Mojmap（四构建实测） |
| loaders | `environments/loaders/fabric-vs-neoforge-1.21.x.md` | Fabric ↔ NeoForge，MC 1.21.x，Mojmap |

版本卡覆盖（结构事实级）与缺号说明：见 `environments/versions/README.md` 覆盖表（唯一事实来源）。

构建系统要点目前散在 `pitfalls.md` 第一部分 A 组与 `api-verification.md` §3（Loom / ForgeGradle / ModDevGradle 的缓存定位），需要展开时再拆 `environments/build-systems/`。
