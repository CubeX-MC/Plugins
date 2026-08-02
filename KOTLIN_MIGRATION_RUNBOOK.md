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

截至 Metro 收尾（2026-08-02）：

| 插件 | 状态 |
|------|------|
| BookLite / FAWEReplacer / MountLicense / Contract / EcoBalancer / RuleGems / Metro | ✅ main 源码全 Kotlin（仅留 vendored bStats `Metrics.java`） |
| Regions | ✅ 原生 Kotlin |
| Reputations | ✅ 3 个 `.java` 是**故意**保留的 Java API 面（`org.cubexmc.reputations.api`），不要动 |
| **Railway** | ⬜ 167 个 main `.java`，Metro 同源 fork，**下一个** |
| **Clarity** | ⬜ 8 个 main `.java`，且是唯一零 `cubex-*` 模块接入的插件 |
| **modules** | ⬜ cubex-config(16) / cubex-i18n(9) / cubex-scheduler(5)；**cubex-core(9) 按既定最后迁** |

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

一批 5–20 个文件、控制在一次可验证的范围内。**跨批不要留半个包**：同包内互相引用的 `internal` 类要一起迁（Kotlin `internal` 成员对 Java 调用方会改名，混编期会断）。

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

- Railway 是 Metro 的同源 fork，Metro 这一轮的分批顺序、可空性模式、互操作坑**可整套复用**。
- Railway 比 Metro 多：physics / 发车调度 / entity.yml，`tickAccessEnabled=true` 的调度差异。
- Railway 的 `build.gradle.kts` 把 cloud / scoreboardlibrary / geantyref relocate 到了 **`org.cubexmc.metro.lib.*`**（从 Metro 抄来时没改）。ClassLoader 隔离下不影响运行，但命名是错的；要改就单独一个提交、单独验收，**不要混进 Kotlin 迁移批次**。
- `Railway/.claude/worktrees/` 下有两份历史 agent worktree 副本（已 gitignore，未跟踪）。它们会污染"数文件"和全目录 grep——统计一律以 `kotlinMigrationStatus` 或 `Railway/src` 为准。
