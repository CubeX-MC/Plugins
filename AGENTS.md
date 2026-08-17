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
| 做可选跨插件连接 | [`CUBEX_INTEGRATIONS_DESIGN.md`](CUBEX_INTEGRATIONS_DESIGN.md) |

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
- **可选连接不是依赖**：通过 `cubex-integrations` 使用提供方插件 ClassLoader 解析 Bukkit service；消费方不 `implementation`/`compileOnly` 另一个插件，也不 shade 对方 API。`softdepend` 只用于可选加载顺序。
- **新插件要加进 `buildSrc/src/main/kotlin/CubexRelocations.kt` 的 pluginIds**，否则 shadowJar 报 `Key X missing`。
- **提交严格按插件 scope**（`git add -A -- <Plugin>`）：工作区经常有别的项目的并行 WIP，别卷进来。
- **不要把重构和玩法/配置/文案改动混在一个提交里**。
- 推 `main` 会触发 CI 与 9 个公开镜像仓同步 —— **推送前先跟用户确认**。
- 改了 `buildSrc` 会触发全量重编；异常时 `.\gradlew.bat --stop` 后重试。

## 共享模块的参考实现

**Contract 是 `modules/cubex-*` 的参考适配插件**（2026-08-06 确立）。写别的插件时按它抄；两边不一致以 Contract 为准。

具体含义：

- 插件里**不留共享工具的本地副本**。Contract 已删掉自己的 `Text`，改用 `CubexText`（`plugin.text()`）。发现共享模块缺能力，**改 `modules/`，不要在插件里绕过**。
- 新 Kotlin 源码放 `src/main/kotlin`。部分早期迁移插件的 `.kt` 仍在 `src/main/java`，Gradle 已兼容；不要只为移动目录制造大范围无行为 diff。厂商 vendored 的 `Metrics.java` 留在 `src/main/java`。
- 有状态的 store 实现 `Reloadable` + `Terminable`，直接 `bind(store)`，别再手写 `bind(Runnable { store.flush() })`。
- reload 用 `ReloadChain`：分阶段命名、`addIf` 表达"上一步失败就别跑这几步"、`ReloadReport` 告诉服主是哪一段炸的。
- 语言文件所有段（不只 `messages.*`）都走 `I18nService`，值用 MiniMessage。
- 可选服务适配器按 Contract 的 Reputations bridge：本地行为先完成、桥失败只降级、不得缓存跨 reload 的 provider；事务型连接另做幂等领域 API。

这一轮为此补的共享 API（都带测试）：`CubexPlugin.text()/log()/messager()` 放开为 public；`ReloadChain` 加 `ReloadFailurePolicy`/`addIf`/`ReloadReport`；`I18nService.rawOrNull`；`LegacyTextToMiniMessageStep.AngleBrackets.PRESERVE`。

## 已定决策（别"顺手修"）

- **Railway 的源码包就是 `org.cubexmc.metro`，主类 `org.cubexmc.metro.Metro`，与 Metro 完全同名——这是有意保留的**（2026-08-02 用户确认）。理由：Metro 的线路控制等功能更新可以直接搬到 Railway；Metro 与 Railway **本就不支持同时安装**。同理 Railway 的 `build.gradle.kts` 把 cloud / scoreboardlibrary / geantyref relocate 到 `org.cubexmc.metro.lib.*` 也**不要改**。
- **Reputations 的 3 个 `.java` 是故意的 Java API 面**（`org.cubexmc.reputations.api`），不要迁 Kotlin。
- **Clarity 编译到 Java 21**（用 1.21 属性 API），全仓其余插件是 17；`jarGate` 已按各插件的 java release 分别校验。

## 当前迁移基线

- **Kotlin 迁移与 `cubex-core` 接入已于 2026-08-16 收口**：全部插件 opt-in Kotlin 并继承 `CubexPlugin`；五个 `cubex-*` 模块（含后续新增的 `cubex-integrations`）均使用 Kotlin。剩余 `.java` 仅为 vendored `Metrics.java`、Reputations 的公开 Java API，以及 Metro/Railway 的必要互操作 shim；不要为了文件计数迁掉它们。
- Railway 的迁移批次、共享能力审计和“能不能复用 Metro `.kt`”的两步判据保留在 [`KOTLIN_MIGRATION_RUNBOOK.md`](KOTLIN_MIGRATION_RUNBOOK.md)，供后续同源维护使用；它们不再是待办清单。

## 已知脆弱点

- `Metro:TrainTravelDisplayControllerTest.shouldThrottleUpdatesToConfiguredInterval` 偶发 `World unloaded`（Bukkit `Location` 对 mock World 持弱引用，被 GC 即抛），重跑即过。
- `Railway/.claude/worktrees/` 有历史 agent worktree 副本（已 gitignore、未跟踪），会污染全目录 grep 与文件计数；统计以 `kotlinMigrationStatus` 或 `<Plugin>/src` 为准。
- PowerShell 5.1 的 `Set-Content -Encoding utf8` 会写 BOM，javac 直接报 `illegal character: '﻿'`；脚本改源码文件请用 `[System.IO.File]::WriteAllText($p, $t, (New-Object System.Text.UTF8Encoding($false)))`。
