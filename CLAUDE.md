# CubeX-Plugins — agent 须知

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

## 硬约束

- **每个插件必须能单独安装**：不要引入插件间的编译期依赖；有状态的共享服务做成独立插件（如 Reputations），无状态共享代码走 `modules/cubex-*`。
- **新插件要加进 `buildSrc/src/main/kotlin/CubexRelocations.kt` 的 pluginIds**，否则 shadowJar 报 `Key X missing`。
- **提交严格按插件 scope**（`git add -A -- <Plugin>`）：工作区经常有别的项目的并行 WIP，别卷进来。
- **不要把重构和玩法/配置/文案改动混在一个提交里**。
- 推 `main` 会触发 CI 与 9 个公开镜像仓同步 —— **推送前先跟用户确认**。
- 改了 `buildSrc` 会触发全量重编；异常时 `.\gradlew.bat --stop` 后重试。

## 已知脆弱点

- `Metro:TrainTravelDisplayControllerTest.shouldThrottleUpdatesToConfiguredInterval` 偶发 `World unloaded`（Bukkit `Location` 对 mock World 持弱引用，被 GC 即抛），重跑即过。
- `Railway/.claude/worktrees/` 有历史 agent worktree 副本（已 gitignore、未跟踪），会污染全目录 grep 与文件计数；统计以 `kotlinMigrationStatus` 或 `<Plugin>/src` 为准。
