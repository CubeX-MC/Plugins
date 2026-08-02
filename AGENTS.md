# CubeX-Plugins — agent 须知

> 本仓库由 **Claude Code 与 Codex 共同推进**。这份 `AGENTS.md` 是唯一权威的 agent 入口，
> `CLAUDE.md` 只是指向本文的薄封面。**新增的约定写进这里**，不要写进某个工具专属文件。

Minecraft 插件 monorepo（Gradle + `buildSrc` 约定插件）。产物是 **N 个可独立安装的插件 jar**，共享 `cubex-*` 模块 shade 进各 jar。

## 上手先看哪份

| 你要做的事 | 读 |
|---|---|
| 构建 / 跑测试 / 出 jar | [`README.md`](README.md) |
| 把某个插件迁到 Kotlin | [`KOTLIN_MIGRATION_RUNBOOK.md`](KOTLIN_MIGRATION_RUNBOOK.md)（流程）+ [`KOTLIN_STYLE_GUIDE.md`](KOTLIN_STYLE_GUIDE.md)（规则与互操作坑） |
| 跨插件方向 / 优先级 | [`ROADMAP.md`](ROADMAP.md) |
| 为什么是这套架构 | [`ARCHITECTURE_PROPOSAL.md`](ARCHITECTURE_PROPOSAL.md) §7/§8、各 `CUBEX_*_DESIGN.md` |

## 常用命令

```powershell
.\gradlew.bat :<Plugin>:build              # 编译 + 测试 + shadowJar
.\gradlew.bat :<Plugin>:test --tests "..." # 过滤跑测试
.\gradlew.bat :<Plugin>:jarGate            # 部署 jar 门禁
.\gradlew.bat jarGateAll                   # 全部插件的门禁
.\gradlew.bat kotlinMigrationStatus        # 各插件 Java/Kotlin 文件数与 opt-in 状态
.\gradlew.bat build                        # 全仓构建 + 测试
```

**Windows 必须用 PowerShell 跑 `.\gradlew.bat`**（仓库路径含空格，git-bash 下 `./gradlew` 会失败）。
Linux/CI 上是 `./gradlew`，任务名相同。

## 硬约束

- **每个插件必须能单独安装**：不要引入插件间的编译期依赖；有状态的共享服务做成独立插件（如 Reputations），无状态共享代码走 `modules/cubex-*`。
- **新插件要加进 `buildSrc/src/main/kotlin/CubexRelocations.kt` 的 pluginIds**，否则 shadowJar 报 `Key X missing`。
- **提交严格按插件 scope**（`git add -A -- <Plugin>`）：工作区经常有别的项目的并行 WIP，别卷进来。
- **不要把重构和玩法/配置/文案改动混在一个提交里**。
- 推 `main` 会触发 CI 与 9 个公开镜像仓同步 —— **推送前先跟用户确认**。
- 改了 `buildSrc` 会触发全量重编；异常时 `.\gradlew.bat --stop` 后重试。

## 已定决策（别"顺手修"）

- **Railway 的源码包就是 `org.cubexmc.metro`，主类 `org.cubexmc.metro.Metro`，与 Metro 完全同名——这是有意保留的**（2026-08-02 用户确认）。理由：Metro 的线路控制等功能更新可以直接搬到 Railway；Metro 与 Railway **本就不支持同时安装**。同理 Railway 的 `build.gradle.kts` 把 cloud / scoreboardlibrary / geantyref relocate 到 `org.cubexmc.metro.lib.*` 也**不要改**。
- **Reputations 的 3 个 `.java` 是故意的 Java API 面**（`org.cubexmc.reputations.api`），不要迁 Kotlin。
- **Clarity 编译到 Java 21**（用 1.21 属性 API），全仓其余插件是 17；`jarGate` 已按各插件的 java release 分别校验。

## 进行中的工作

- **Railway Kotlin 迁移**在分支 `kotlin/railway`（origin 已有该分支），原始源码 71/167 已迁（当前 `97 Java / 71 Kotlin`，多出的 Java 是有意保留的 PlaceholderAPI 可空 shim）；下一批是 `MetroAPI`。接力点、细化后的批次顺序、以及"能不能照抄 Metro 的 `.kt`"的两步判据都在 [`KOTLIN_MIGRATION_RUNBOOK.md`](KOTLIN_MIGRATION_RUNBOOK.md)。**接手前先读那一节**——已经有三个文件因为只比 Java 不看 Metro 的 `.kt` 历史而差点被抄错，其中两个没有测试会报警。

## 已知脆弱点

- `Metro:TrainTravelDisplayControllerTest.shouldThrottleUpdatesToConfiguredInterval` 偶发 `World unloaded`（Bukkit `Location` 对 mock World 持弱引用，被 GC 即抛），重跑即过。
- `Railway/.claude/worktrees/` 有历史 agent worktree 副本（已 gitignore、未跟踪），会污染全目录 grep 与文件计数；统计以 `kotlinMigrationStatus` 或 `<Plugin>/src` 为准。
- PowerShell 5.1 的 `Set-Content -Encoding utf8` 会写 BOM，javac 直接报 `illegal character: '﻿'`；脚本改源码文件请用 `[System.IO.File]::WriteAllText($p, $t, (New-Object System.Text.UTF8Encoding($false)))`。
