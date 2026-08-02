# Kotlin 迁移执行手册

> **谁读这份**：接手"把某个插件迁到 Kotlin"的执行者（人或 agent）。
> **先读**：[`KOTLIN_STYLE_GUIDE.md`](KOTLIN_STYLE_GUIDE.md)（规则 + 互操作坑）。
> 战略与决策依据在 [`CUBEX_KOTLIN_MIGRATION_DESIGN.md`](CUBEX_KOTLIN_MIGRATION_DESIGN.md)（§10 为权威结论），本文只讲**怎么执行一轮**。

---

## 当前进度

用命令看，不要靠猜（也不要自己写脚本数文件）：

```powershell
.\gradlew.bat kotlinMigrationStatus
```

截至 2026-08-02：

| 插件 | 状态 |
|------|------|
| BookLite / FAWEReplacer / MountLicense / Contract / EcoBalancer / RuleGems | ✅ main 源码全 Kotlin（仅留 vendored bStats `Metrics.java`） |
| Metro | ✅ 实现已全 Kotlin；保留 `Metrics.java` 与两个必要的 Java 互操作 shim |
| Regions | ✅ 原生 Kotlin |
| Reputations | ✅ 3 个 `.java` 是**故意**保留的 Java API 面（`org.cubexmc.reputations.api`），不要动 |
| **Railway** | 🚧 **进行中**，见下节 |
| **Clarity** | ⬜ 8 个 main `.java`，且是唯一零 `cubex-*` 模块接入的插件 |
| **modules** | ⬜ cubex-config(16) / cubex-i18n(9) / cubex-scheduler(5)；**cubex-core(9) 按既定最后迁** |

### Railway 接力点

**分支 `kotlin/railway`（origin 已有该分支）**，原始源码 75/167 已迁；当前计数为 93 Java / 75 Kotlin，
`:Railway:build` 与 `:Railway:jarGate` 绿。已整包完成：`util`、`update`、`event`、`spatial`、
`persistence`、`model`、`estimation`、`config`、`api`；`placeholder` 的实现已迁，leaf 枚举/接口已清空。
`service` 的叶子批次（`PriceService`、`TicketService`、`LineStatusService`）也已完成；下一批进入
service 命令域。
`model` 最后完成的 `EntityDisplayConfig` / `EntityModelController` 是 Railway 独有实现，已从 Railway
自己的 Java 机械迁移；其中 `DisplaySettings` 保留 JVM record 形状，供剩余 Java 调用方继续使用
`spacing()` / `offsetY()` / `properties()`，静态 helper 也保留真正的 Java static bridge。
`RailwayPlaceholders` 因 PlaceholderAPI 把 `params` 标成非空、而旧实现明确容忍 null，新增了一个
`NullablePlaceholderExpansion.java` 兼容 shim。它是有意保留的 Java 文件，因此当前 main 文件总数
为 168；进度分母 167 仍指迁移开始时的原始源码数，不要为了让计数好看而删除这个 shim。

剩余（按建议顺序）：

| 顺序 | 批次 | 文件 / 规模 | 边界说明 |
|---|---|---:|---|
| 1 | service 命令域 | `LineCommandService` + `LineSelectionService` + `PortalCommandService` + `StopCommandService`，4 文件 / 868 行 | 下一批；与对应 service 测试一起验证 |
| 2 | virtual service | `VirtualTrain` + `VirtualTrainPool`，2 文件 / 1184 行 | 强耦合，不拆开；跑 `VirtualTrainPoolTest` |
| 3 | dispatch runtime | `LineService` + `LineServiceManager` + `TrainSpawner` + 两个 strategy，5 文件 / 1295 行 | 强耦合边界；完成后 service 包清空 Java |
| 4 | `manager` | 7 文件 / 3126 行 | 继续拆批；`LineManager` 等超大类可单独提交 |
| 5 | `train` | 13 文件 / 3049 行 | `TrainInstance`、movement/display 等按调用簇拆批 |
| 6 | `physics` | 18 文件 / 2451 行 | Railway 独有；Kinematic / Reactive / bridge 分批 |
| 7 | GUI | core 5 + view 9 + controller 10，24 文件 / 3469 行 | core → view → controller；`GuiHolder` 沿用 Java shim 模式 |
| 8 | 外围入口 | integration 3 → lifecycle 3 → command 7 → listener 4 | 每个包或调用簇独立验证 |
| 9 | 主类 | `Metro.java` | 最后迁；`Metrics.java` 永远保留 Java |

这里的“行数”只用于控制审查面，计数仍以 `kotlinMigrationStatus` 为准。此前的外围 4 文件已按
`TravelTimeEstimator + RailwayPlaceholders` / `ConfigFacade` / `MetroAPI` 拆成三批，避免把 1939 行、
配置与 API 两个兼容性面混进同一提交。

`ConfigFacade` 已完成 Metro 复用判定：Railway Java 与 Metro 迁移前 Java 不同，且 Metro Kotlin
迁移后又被 `c68de20` 修改；两项条件都不满足。必须从 Railway 自己的 Java 机械迁移，Metro 版本
只能参考写法，不能复制。

`MetroAPI` 的 Railway Java 与 Metro 迁移前 Java 仅差结尾换行，因此复用了迁移提交 `36ab5dc`
当时的 Kotlin，而非后来被 `383f683` / `e726cad` 修改的版本。Railway 的 `VaultIntegration` 仍是
Java，调用保留 `isEnabled()`；不要改成 Metro Kotlin 调用方的 property 写法。

service 叶子批次的三个 Railway Java 都与各自 Metro 迁移前 Java 一致。`LineStatusService` 的 Kotlin
迁移后没有功能提交，可直接复用；`PriceService` 与 `TicketService` 后来都被 `6e56184` 改过，因此
只复用各自迁移提交（`284a177` / `52feeee`）中的 Kotlin。不要把 Metro 后来改成“环线只按配置方向
计费”或“车主入账失败时退款”的行为混进 Railway 的纯迁移批次。`TicketService` 同样保留 Railway
Java `VaultIntegration.isEnabled()` 的显式调用；`TicketTransaction.isCharged()` 继续是 Java 只读面，
通过 `@JvmSynthetic internal set` 供外层 service 更新，不要照搬 Metro 版本而公开 `markCharged()`。

---

## 一轮迁移的标准流程

### 0. 开工前

1. 工作区干净、分支从最新 `main` 切出（`kotlin/<plugin>-<批次>` 或 `kotlin/<plugin>`）。
2. 跑一次基线：`.\gradlew.bat :<Plugin>:build`，确认**迁移前**就是绿的。
3. 插件若未 opt-in，把 `build.gradle.kts` 的 `id("cubex-plugin")` 改成 `id("cubex-kotlin-plugin")`（**只改这一行**，纯 Java 插件不受影响）。
   - 白名单 shadow 模式的插件（如 Contract）还要显式 `include(dependency("org.jetbrains.kotlin:.*:.*"))`，否则 relocate 后的 stdlib 不进 jar，运行期 `NoClassDefFound`。

### 1. 分批

**叶子优先，主类最后**。Metro 实际用的顺序，可直接照抄：

```
model / util / event  →  service / manager  →  integration / lifecycle
  →  command 层  →  GUI（core → view → controller）  →  listener  →  主类
```

通常一批 5–20 个小文件；对 Railway 这类大类密集的插件，优先把一批控制在约 400–1200 行。
单个 700+ 行 facade/主类可以独立成批，超过 1200 行只在类型强耦合、拆开反而制造混编风险时接受。
文件数和行数都是审查面提示，不是硬门禁。**跨批不要留半个强耦合簇**：同包内互相引用的
`internal` 类要一起迁（Kotlin `internal` 成员对 Java 调用方会改名，混编期会断）。

### 2. 每批的循环

```powershell
# 1) 读 .java → 写同名 .kt（同目录，保持包名/类名/方法签名）
# 2) 删掉对应 .java
# 3) 编译
.\gradlew.bat :<Plugin>:compileKotlin
# 4) 全量构建 + 测试
.\gradlew.bat :<Plugin>:build
# 5) 只提交这个插件的改动
git add -A -- <Plugin>
git commit -m "refactor(<plugin>): migrate <这一批> to Kotlin"
```

编译器是你的清单：`compileKotlin` 报的每一个 "Argument type mismatch: actual type is 'X?'" 都是一处**原本被平台类型掩盖的可空性**，逐个按下面的模式处理，不要用 `!!` 糊过去。

### 3. 收尾验收

```powershell
.\gradlew.bat :<Plugin>:build      # 编译 + 全部测试
.\gradlew.bat :<Plugin>:jarGate    # 部署 jar 门禁(下节)
.\gradlew.bat build                # 确认没碰坏别的插件
```

上服前建议再跑一次 `.\gradlew.bat :<Plugin>:runServer` 做真机冒烟（Metro 这一轮没做，属于已知缺口）。

---

## 部署 jar 门禁

```powershell
.\gradlew.bat :<Plugin>:jarGate    # 单个插件
.\gradlew.bat jarGateAll           # 全部插件
```

`jarGate` 自动校验（失败会直接 fail build，不用再手工解包 jar）：

- `kotlin/**` 零残留（stdlib 必须 relocate 到 `org.cubexmc.<id>.libs.kotlin`）；
- 已 opt-in 的插件 jar 里**有** relocate 后的 stdlib，未 opt-in 的插件**一个 Kotlin 类都没有**；
- 没有 `kotlin/reflect/{full,jvm}` 实现类；
- 本仓库自己的类字节码版本 = 该插件的 java release（Clarity 覆盖成 21，其余 17）；relocate 目标命名空间下的第三方类自动豁免（从 shadowJar 的 relocator 列表推出来，不写死）；
- `plugin.yml` 在。

门禁**不查**的、仍要人工确认的：`plugin.yml` 内容、bStats id、sqlite 平台数、adventure 是否单份、现代化成果（lang/config 版本）未回退。

---

## 反复出现的处理模式

**可空 manager + 受检访问器**（构造器接受 null 让测试能传未 stub 的 mock，真实调用懒检查、不静默吞）：

```kotlin
class LineCommandService(lineManager: LineManager?) {
    private val lineManagerRef: LineManager? = lineManager
    private val lineManager: LineManager
        get() = lineManagerRef ?: throw NullPointerException("lineManager")
}
```

**主类属性**：调用方原本就当非空用的 → `lateinit var xxx: T; private set`；Java 侧原本 `!= null` 判过的（Metro 的 portalManager / railProtectionManager / vaultIntegration / scoreboardLibrary）→ 保持 `T?`，让调用方的守卫继续成立。关闭钩子里用 `::xxx.isInitialized` 代替原来的 null 判断。

**可选命令参数必须可空**：Cloud 与反射式 Bukkit fallback 都会给缺省的 `[optional]` 传 `null`。

**回调引用**：不要 `::foo`（可能触发 `KFunction`，约定里排除了 reflect 实现），用普通 lambda。

**Java record**：原类型已经是 record、且 Java 调用方仍使用 `component()` 访问器时，用
`@JvmRecord data class` 保留 record 形状；不要直接改成普通 `data class`，否则 Java 访问器会从
`spacing()` 变成 `getSpacing()`。这不属于“把普通领域类顺手 data class 化”。

**仍被 Java 调用的 static**：放进 companion 后给入口加 `@JvmStatic`，并用 Java 测试或
`compileJava` 验证真正的 static bridge。Kotlin 没有 package-private；包内 Java 测试仍需直接调用时，
可让承载类保持 `internal`、把所需 bridge 声明为普通 JVM 可见方法，但不要把它当成新公共 API 承诺。

**第三方 `@NotNull` override 与旧 null 容错冲突**：如果 Java 实现明确检查过 null，而 Kotlin 因
第三方注解不允许把 override 参数声明成可空，不要删守卫或接受 Intrinsics NPE。增加一个最小 Java
抽象 shim，在 override 参数上显式 `@Nullable`，让 Kotlin 子类继续实现可空契约；
`NullablePlaceholderExpansion` 是 Railway 的现成例子。

**空值语义别改**：原来会 NPE 的路径，改成早返回/抛 `IllegalStateException` 都要在提交信息里说明；原来返回 null 的公开契约（如 GUI holder 的 `getInventory()`）必须保住。

---

## 提交与分支

- 提交**严格按插件 scope**：`git add -A -- <Plugin>`。仓库里常有别的项目的并行 WIP，不要卷进来。
- 提交信息：`refactor(<plugin>): migrate <范围> to Kotlin`，正文写清楚**为什么某处不是纯机械转换**（保留的 Java shim、改过的测试、可空性收敛）。
- 每批一个提交；一个插件迁完再合 `main`（合并 + 推送前跟用户确认，推 `main` 会触发 CI 和 9 个公开镜像同步）。

---

## Windows 注意

- 路径含空格，**必须** PowerShell `.\gradlew.bat`；git-bash 的 `./gradlew` 会报 `GradleWrapperMain not found`。
- 别用 PowerShell 5.1 的 `Set-Content -Encoding utf8` 写源码文件——它会加 BOM，javac 直接报 `illegal character: '﻿'`。要脚本改文件就用 `[System.IO.File]::WriteAllText($p, $t, (New-Object System.Text.UTF8Encoding($false)))`。

---

## 下一轮（Railway）已知注意点

- Railway 是 Metro 的同源 fork，Metro 这一轮的分批顺序、可空性模式、互操作坑**可整套复用**。对照 Metro 的同名文件改，通常八成能直接套。
- **想直接抄 Metro 的 `.kt` 之前，先跑两步校验**，两步都过才可以照抄：

  ```powershell
  # ① Railway 的 .java 与 Metro 迁移前的 .java 是否一致（只差结尾换行 = 一致）
  $p = "Metro/src/main/java/org/cubexmc/metro/<pkg>/<Name>.java"
  $del = git log --diff-filter=D --format=%H -1 -- $p
  git show "$del^:$p" > $env:TEMP\m.java
  git diff --no-index -- $env:TEMP\m.java "Railway/src/main/java/org/cubexmc/metro/<pkg>/<Name>.java"

  # ② Metro 的 .kt 自迁移后有没有被功能提交改过（>1 个提交就不能照抄）
  git log --oneline -- "Metro/src/main/java/org/cubexmc/metro/<pkg>/<Name>.kt"
  ```

  只做 ① 会中招，已实际踩到三次（14 个候选里只有 6 个真正合格）：

  | 文件 | Java 一致？ | Metro 迁移后又改了什么 | 后果 |
  |---|---|---|---|
  | `TrainScoreboardController` | 是 | travel feedback 让 `MOVING_BETWEEN_STATIONS` 也刷新记分板 | 把 Metro 玩法偷渡进 Railway（Railway 的测试当场报错） |
  | `update/DataFileUpdater` | 是 | 删掉了 `linkedPortalId → linked` 字段重命名 | **静默丢一步数据迁移**，无测试兜底 |
  | `integration/VaultIntegration` | 是 | 重写成会动态重解析经济服务的 `Listener` | 引入 Railway 没有的行为，无测试兜底 |

  后两个都没有测试会报警——所以 ② 是硬要求，不是可选检查。
- Railway 比 Metro 多：physics / 发车调度 / entity.yml，`tickAccessEnabled=true` 的调度差异。
- **Railway 的源码包就是 `org.cubexmc.metro`（主类 `org.cubexmc.metro.Metro`），与 Metro 完全同名，这是有意保留的**——方便 Metro 的功能更新直接搬过来，两者本就不支持同时安装。`build.gradle.kts` 里 relocate 到 `org.cubexmc.metro.lib.*` 同理。**迁移时不要顺手改包名或 relocate 目标。**
- 因为同包同名，改 Railway 时**务必确认自己打开的是 `Railway/src/...` 而不是 `Metro/src/...`**；提交前 `git status` 看一眼路径前缀。
- `Railway/.claude/worktrees/` 下有两份历史 agent worktree 副本（已 gitignore，未跟踪）。它们会污染"数文件"和全目录 grep——统计一律以 `kotlinMigrationStatus` 或 `Railway/src` 为准。

### 什么时候可以直接复用 Metro 的 `.kt`

同源 fork 让"把 Metro 的 `.kt` 拷过来"很诱人，但**只有同时满足两个条件才安全**：

1. **Railway 的 `.java` 与 Metro 迁移当时的 `.java` 一致**（`git diff -w`，忽略空白）；
2. **Metro 的 `.kt` 在迁移提交之后没有再被改过**（`git log <migrationCommit>..HEAD -- <ktPath>` 为空）。

条件 2 是真正的坑：Metro 的 Kotlin 文件后来被玩法提交改过（例如 `c68de20` 改了 `PriceRule`/`TextUtil`/`MetroConstants`，`383f683` 删掉了整套传送门配对）。直接拷当前版本 = 把 Metro 的玩法变更偷偷带进 Railway。

两条都满足才拷当前 `.kt`；条件 1 满足、条件 2 不满足时，拷**迁移当时那一版**：

```powershell
git show <migrationCommit>:Metro/src/main/java/org/cubexmc/metro/<Path>.kt
```

条件 1 不满足（Railway 有自己的差异，如 `MetroTextRenderer` 多一个 trusted 占位符 `color_code`、`OwnershipUtil` 权限节点是 `railway.*`、`SchedulerUtil` 开了 tick 计数、`Portal` 多了 `linkedPortalId` 与 `linked` 配置键）→ **从 Railway 自己的 `.java` 转**，Metro 的 `.kt` 只当写法参考。

判定脚本（一次看一批）：

```powershell
$del = (git log --diff-filter=D --format=%H -1 -- "Metro/src/main/java/org/cubexmc/metro/<Path>.java")
git diff --no-index -w -- <metro-java-at-$del^> <railway-java>   # 条件 1
git log --oneline "$del..HEAD" -- "Metro/src/main/java/org/cubexmc/metro/<Path>.kt"  # 条件 2:输出为空才安全
```

**别用"只差 1 行 = 只差换行"这类速判**——`MetroTextRenderer` 的 trusted 列表就写在一行里，只差 1 行恰恰是实质差异。最终以 `:Railway:build`（全部单测）为准。
