# minecraft-mod-dev-skill

Thatgfsj-minecraft 组织的 **通用 Minecraft 模组开发技能包**（AI agent 可直接加载的 skill）：让 agent 在开发任何 Minecraft Java 模组之前，先识别目标项目的 **Minecraft 版本、Mod Loader、映射体系、Java 版本、构建系统与项目架构**，再根据实际环境进行开发、修改、构建、测试和调试。

```
Architecture:                     Generic / extensible
                                  （版本与 Loader 是 agent 运行时识别的项目事实，不是 Skill 前提）

Currently validated environments: （这是 reference 覆盖范围，不代表 Skill 的支持边界）
- 已验证差异经验：Minecraft 1.21.1 ↔ 1.21.11 × Fabric / NeoForge（Mojmap，Java 21）
- 版本卡（结构事实）：覆盖清单的唯一事实来源见 references/environments/versions/README.md
```

## 解决什么问题

Minecraft Mod API 跨版本 / 跨 Loader 差异巨大（类名、签名、注册、事件、网络、命令、侧归属、数据机制都在变）。AI agent 最常见的失败模式是**凭记忆直接生成代码**，或把某个见过的版本 / Loader 当成默认前提。本 Skill 用两条硬规则对冲：

1. **Phase 0 强制项目侦察**：任何实现之前，读构建配置、loader metadata、映射配置、已有代码，产出环境卡（版本 / Loader / 映射 / Java / 构建系统 / 架构 / 项目约定）。
2. **不凭记忆猜 API**：有不确定必须核实——项目代码 → 依赖 → 映射源码 → 原版 / Loader 源码 → 文档 → 最小编译探针。

## 核心模型

```
Skill Core（怎么发现、怎么判断、怎么开发、怎么验证）
   ↓
Phase 0 — Project Discovery → Environment Identification（环境卡）
   ↓
按需加载 environments/ 对应知识（版本进 versions/<版本号>/，Loader 进 loaders/）
   ↓
Understand → Implement → Build → Test / E2E → Debug → 回归
```

新增 Minecraft 版本 = 在 `references/environments/versions/` 加一个版本文件夹；新增 Loader = 在 `loaders/` 加文件。**不修改核心行为。**

## 内容

```
SKILL.md                            主技能：Agent 操作手册（13 节，环境无关）
references/
  README.md                         索引、加载策略、扩展与命名约定
  environment-discovery.md          Phase 0 侦察手册（检查清单、信号判据、冲突处理）
  api-verification.md               API 核实规程（找 jar、读源码、记忆陷阱清单）
  pitfalls.md                       踩坑手册（通用坑 + 案例研究）
  e2e-testing.md                    专用服务器 + 服务端权威断言的 E2E 体系
  environments/
    versions/README.md              版本知识索引（覆盖表、版本卡模板、新增流程）
    versions/1.20.1/ … 1.21.11/     各版本文件夹：README.md 版本卡；已验证差异放 <旧版本>/vs-*.md
    loaders/fabric-vs-neoforge-1.21.x.md   已验证：Fabric ↔ NeoForge 差异（1.21.x）
```

## 适用场景

- 新建 Minecraft 模组项目（任意版本 / Loader；先环境识别，再脚手架）
- 修改 / 移植已有模组（先识别项目环境与约定，再动手）
- 跨版本 / 跨 Loader 迁移（版本对差异对照 + 编译两遍 + 行为测试兜底）
- 排查交互类 bug（界面闪关、客户端与服务端行为不一致、数据静默丢失）
- 搭建模组的自动化 E2E 测试（机器人 + 服务端权威断言）

## 安装（AI agent）

把本仓库克隆到 agent 的 skills 目录（如 `~/.agents/skills/minecraft-mod-dev/` 或项目 `.claude/skills/`），保持 `SKILL.md` 在仓库根目录。agent 会按 frontmatter 的 description 自动触发，或在开发 Minecraft 模组时手动引用。

## 维护约定

- `SKILL.md` 与通用 references 保持**环境无关**：不新增固定版本 / Loader 假设；具体版本经验只进 `environments/`。
- 新环境知识按 `references/README.md` 的"新增环境知识"流程沉淀；版本卡只写有把握的结构事实，已验证经验必须带验证方式。
- 通用坑按"症状 → 根因 → 修复"追加进 `pitfalls.md` 第一部分；超出适用范围的结论必须标注范围。
- 所有结论必须有源码 / 日志证据。
