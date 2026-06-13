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
│     ├─ CubexVersions.kt / CubexDependencies.kt / CubexRelocations.kt
├─ modules/                 # 共享模块(无 shadowJar,被各插件依赖后 shade 进去)
│  ├─ cubex-core/  cubex-scheduler/  cubex-config/  cubex-i18n/
└─ <各插件>/                # BookLite FAWEReplacer MountLicense Contracts
                            # EcoBalancer RuleGems Metro Railway Clarity Reputations
```

### Java vs Kotlin 插件

- 纯 Java 插件：`plugins { id("cubex-plugin") }`
- 已迁 Kotlin 的插件：`plugins { id("cubex-kotlin-plugin") }`（逐插件 opt-in，纯 Java 插件零影响、不引入 stdlib）

---

## 常见排查

- **`GradleWrapperMain not found`**：你在 git-bash 跑了 `./gradlew`。改用 PowerShell `.\gradlew.bat`。
- **改了 `buildSrc` 后构建异常**：`buildSrc` 变更会触发全量重编，必要时 `.\gradlew.bat --stop` 后重试。
- **验证 jar 内容**（relocate / 无 kotlin-reflect / sqlite 平台数 / bytecode 版本等）：解包 `build/libs/<Plugin>-<version>.jar` 自查；Kotlin 迁移的验收门禁见 `KOTLIN_STYLE_GUIDE.md`。
