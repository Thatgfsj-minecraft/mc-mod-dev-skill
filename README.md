# minecraft-mod-dev-skill

Thatgfsj-minecraft 组织的 Minecraft 模组开发技能包（AI agent 可直接加载的 skill），内容全部来自 [handy-shulkers](https://github.com/Thatgfsj-minecraft/handy-shulkers) 的实战沉淀：一个同时维护 MC 1.21.1 / 1.21.11 × Fabric / NeoForge 四个构建、带专用服务器 E2E 测试体系的真实模组项目。

## 内容

```
SKILL.md                     主技能：环境、布局、双版本/双加载器规则、流程
references/
  version-diffs.md           1.21.1 ↔ 1.21.11 Mojmap API 差异逐条对照（含证据）
  loader-diffs.md            Fabric ↔ NeoForge 入口/事件/构建脚本对照
  pitfalls.md                踩坑手册：症状 → 根因 → 修复（17 条，全部实战）
  e2e-testing.md             专用服务器 + mineflayer + RCON 测试体系与断言模式
```

## 适用场景

- 新建 Minecraft 模组项目（双版本/双加载器脚手架与版本坐标）
- 移植/同步模组到新的 MC 版本（API 差异速查）
- 排查"界面闪关""手持床不睡/瞬间醒""配置项复位""依赖解析失败"这类问题
- 搭建模组的自动化 E2E 测试（机器人 + 服务端权威断言）

## 安装（AI agent）

把本仓库克隆到 agent 的 skills 目录（如 `~/.agents/skills/minecraft-mod-dev/` 或项目 `.claude/skills/`），保持 `SKILL.md` 在仓库根目录。agent 会按 frontmatter 的 description 自动触发，或在开发 Minecraft 模组时手动引用。

## 维护约定

- 所有结论必须有源码/日志证据；新踩的坑以"症状 → 根因 → 修复"格式追加进 `references/pitfalls.md`。
- 新 MC 版本适配完成后，把新差异追加进 `references/version-diffs.md`，并更新 SKILL.md 的版本坐标表。
