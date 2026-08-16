# CubeX-Plugins

CubeX 服务器插件 monorepo。开发用单仓库，产物仍是 **N 个可独立安装的插件 jar**（共享 `cubex-*` 模块经 shadow 打进各 jar，Bukkit 每插件独立 ClassLoader 天然隔离）。

构建工具：**Gradle**（Kotlin DSL + `buildSrc` 约定插件 + 中央版本管理）。

---

## 快速开始

> **Windows 必读**：本仓库路径含空格（`MC server`），**必须用 PowerShell 跑 `.\gradlew.bat`**。
> git-bash 下 `./gradlew` 会因路径空格报 `GradleWrapperMain not found`。

```powershell
# 构建单个插件(含其 shadowJar 部署产物)
.\gradlew.bat :RuleGems:build

# 只打单个插件的部署 jar(relocate + 瘦身后的成品)
.\gradlew.bat :RuleGems:shadowJar

# 跑单个插件的测试
.\gradlew.bat :Metro:test

# 跑某个包下的测试(加 --tests 过滤)
.\gradlew.bat :Metro:test --tests "org.cubexmc.metro.update.*"

# 启动本地测试服(run-paper, MC 1.20.1;插件已自动装入)
.\gradlew.bat :Metro:runServer
```

---

## 聚合任务（根项目）

| 命令 | 作用 |
|------|------|
| `.\gradlew.bat shadowJarAll` | 构建**所有插件**的部署 jar（modules 无 shadowJar，自动排除） |
| `.\gradlew.bat buildAllPlugins` | 构建所有子项目（含测试） |
| `.\gradlew.bat jarGateAll` | 对所有插件跑部署 jar 门禁 |
| `.\gradlew.bat kotlinMigrationStatus` | 打印各子项目 Java/Kotlin 文件数与 Kotlin opt-in 状态 |
| `.\gradlew.bat cleanAll` | 清理所有子项目 |
| `.\gradlew.bat :RuleGems:clean` | 只清理单个子项目 |

---

## 产物路径

每个插件的部署 jar 输出到：

```
<Plugin>/build/libs/<Plugin>-<version>.jar
```

- 这份是 **shaded 成品**（classifier 为空），直接丢进服务器 `plugins/` 即可。
- 同目录的 `<Plugin>-<version>-plain.jar` 是未 shade 的原始 jar（classifier `plain`），**不要**用于部署。

部署 jar 的统一约束（由 `cubex-plugin` 约定插件保证）：
- 第三方依赖 relocate 到插件私有命名空间，避免插件间冲突。
- sqlite 原生库瘦身：只保留 MC 服务器常见平台（Windows-x64、Linux-x64、Linux-aarch64、Linux-Musl-{x64,aarch64}）。
- 绝不 relocate `sqlite-jdbc`（依赖原生库路径）。
- 排除 `META-INF/maven/**` 等无用条目。

---

## 项目结构

```
plugins/
├─ settings.gradle.kts      # 子项目清单
├─ build.gradle.kts         # 聚合任务(shadowJarAll / buildAllPlugins / cleanAll)
├─ buildSrc/                # 约定插件 + 中央版本/依赖/relocation 管理
│  └─ src/main/kotlin/
│     ├─ cubex-plugin.gradle.kts          # 通用约定(Java 插件:shadow/瘦身/runServer)
│     ├─ cubex-kotlin-plugin.gradle.kts   # Kotlin 插件 opt-in(额外 kotlin("jvm") + stdlib relocate)
│     ├─ cubex-kotlin-library.gradle.kts  # Kotlin 共享模块约定(Java 17/JUnit/互操作参数)
│     ├─ CubexVersions.kt / CubexDependencies.kt / CubexRelocations.kt
├─ modules/                 # 共享模块(无 shadowJar,被各插件依赖后 shade 进去)
│  ├─ cubex-core/  cubex-scheduler/  cubex-config/  cubex-i18n/
└─ <各插件>/                # BookLite FAWEReplacer MountLicense Contracts
                            # EcoBalancer RuleGems Metro Railway Clarity Reputations
```

### Kotlin 约定插件

- 可部署插件：`plugins { id("cubex-kotlin-plugin") }`（在通用 shadow/runServer 约定上增加 Kotlin 与 stdlib relocation）。
- `modules/cubex-*` 共享库：`plugins { id("cubex-kotlin-library") }`（不生成 shadowJar，由插件 shade）。
- 仓库已完成 Kotlin opt-in；遗留 `.java` 是 vendored 源码、公开 Java API 或互操作 shim。

---

## 常见排查

- **`GradleWrapperMain not found`**：你在 git-bash 跑了 `./gradlew`。改用 PowerShell `.\gradlew.bat`。
- **改了 `buildSrc` 后构建异常**：`buildSrc` 变更会触发全量重编，必要时 `.\gradlew.bat --stop` 后重试。
- **验证 jar 内容**（Kotlin runtime relocate / 无 kotlin-reflect 实现 / 自有类字节码版本 / plugin.yml）：跑 `.\gradlew.bat :<Plugin>:jarGate`，不用再手工解包。门禁规则见 `KOTLIN_STYLE_GUIDE.md` 的 Jar Gate 一节；它**不查** plugin.yml 内容、bStats id、sqlite 平台数、adventure 是否单份，这些仍需人工确认。
- **Kotlin 迁移怎么执行**：见 `KOTLIN_MIGRATION_RUNBOOK.md`（分批顺序、每批的验证循环、可空性处理模式、提交约定）。
