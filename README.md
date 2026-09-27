# minecraft-mod-dev-skill

Thatgfsj-minecraft 组织的 **Minecraft 模组开发通用技能包**（AI agent 可直接加载的 skill）：双版本（1.21.1 / 1.21.11）× 双加载器（Fabric / NeoForge）的环境搭建、项目结构、API 差异速查、E2E 测试体系与发布流程。经验来自组织内实战项目的真实开发与排查记录。

## 内容

```
SKILL.md                     主技能：环境、布局、双版本/双加载器规则、通用工程原则、流程
references/
  version-diffs.md           1.21.1 ↔ 1.21.11 Mojmap API 差异逐条对照
  loader-diffs.md            Fabric ↔ NeoForge 入口/事件/构建脚本对照
  pitfalls.md                第一部分：通用坑（任何 mod 都会遇到）
                             第二部分：案例研究（原项目特有机制，方法可复用、结论不可复用）
  e2e-testing.md             专用服务器 + mineflayer + RCON 测试体系与断言模式
```

## 适用场景

- 新建 Minecraft 模组项目（双版本/双加载器脚手架与版本坐标）
- 移植/同步模组到新的 MC 版本（API 差异速查）
- 排查交互类 bug（界面闪关、客户端与服务端行为不一致、数据静默丢失）
- 搭建模组的自动化 E2E 测试（机器人 + 服务端权威断言）

## 安装（AI agent）

把本仓库克隆到 agent 的 skills 目录（如 `~/.agents/skills/minecraft-mod-dev/` 或项目 `.claude/skills/`），保持 `SKILL.md` 在仓库根目录。agent 会按 frontmatter 的 description 自动触发，或在开发 Minecraft 模组时手动引用。

## 维护约定

- 主内容保持**通用**：写"任何 mod 开发都适用"的经验，不绑定某个具体 mod 的功能机制。
- 特定机制的问题放 `references/pitfalls.md` 第二部分案例研究，并标注"结论不可复用、方法可复用"。
- 所有结论必须有源码/日志证据；新踩的通用坑按"症状 → 根因 → 修复"追加进第一部分。
- 新 MC 版本适配完成后，把新差异追加进 `references/version-diffs.md`，并更新 SKILL.md 的版本坐标表。
