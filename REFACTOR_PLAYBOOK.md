# CubeX 插件体系重构 · 执行手册(给执行 Agent)

> **本文件是「怎么做」的权威施工手册,执行 agent 只需读本文件即可动手。**
> 「为什么这么定」见同目录 [`ARCHITECTURE_PROPOSAL.md`](ARCHITECTURE_PROPOSAL.md)(决策依据,非执行用)。
> 参考实现(只读学习,勿改、勿纳入版本控制):`../reference/NewNanCity-Plugins`。
>
> **monorepo 根目录** = 本目录 `C:\Users\Angus\Desktop\MC server\plugins`,`rootProject.name = "CubeX-Plugins"`。
> 阶段必须**按序**推进(0→1→2→3→4);每阶段**验收门**全绿才能进入下一阶段。

---

## 1. 不可违反的硬约束(护栏)

执行任何步骤都必须满足下列约束;违反则该步骤判定失败、必须回退。

1. **独立发布**:每个插件最终仍产出**单独、可独立安装**的 jar(共享库 shade 进各 jar)。绝不要求用户安装"全家桶"。
2. **零玩法变更**:第 0/1 阶段(构建迁移)和第 3 阶段(接入 core)**不得改变任何游戏内行为**。判定标准:迁移前后产物的类/资源、`plugin.yml`、命令与权限节点保持等价(见各阶段验收门)。
3. **保留每插件的目标 MC / `api-version`**:各插件**故意**支持不同 MC 版本下限(1.16/1.18/1.20),**不得擅自统一**。集中管理的只是**构建基础设施与库版本**,不是各插件的 API 版本。
4. **jar 瘦身过滤器必须保留**:shade/shadow 时对 `sqlite-jdbc` 套用 §6.4 的原生库排除列表(只留 Windows-x64 / Linux-x64 / Linux-aarch64 / Linux-Musl-{x64,aarch64})。
5. **绝不 relocate `sqlite-jdbc`**:它按包路径 `org/sqlite/native/...` 定位原生库,relocate 会破坏加载。可 relocate 的只有 `cubex-*`、`gson`、`jackson` 等纯 Java 库。
6. **第 1 阶段不升级依赖版本**:Maven→Gradle 是**纯构建迁移**,每个插件保留其现有依赖坐标与版本;版本统一是后续独立任务(需测试),不在此阶段做。
7. **不丢 git 历史**:仓库合并用 `git subtree`(§9),各插件历史保留在其子目录。
8. **语言保持 Java**:本手册覆盖路线 B(Gradle + Java)。**不要**在此阶段引入 Kotlin;Kotlin 化是后期路线 C(§10)。
9. **验证未通过 ≠ 完成**:每个子任务结束必须跑对应验收门并贴出证据(命令输出),否则视为未完成。

---

## 2. 目标目录结构(monorepo 终态)

```
plugins/                         # = monorepo 根 (rootProject.name = "CubeX-Plugins")
├── settings.gradle.kts          # 聚合所有子项目
├── build.gradle.kts             # 根脚本(allprojects/subprojects 通用配置 + 聚合任务)
├── gradle.properties            # 并行/缓存/JVM 参数
├── gradlew / gradlew.bat        # wrapper(gradle 8.8)
├── gradle/wrapper/...
├── buildSrc/                    # 中央构建治理
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       ├── CubexVersions.kt     # 所有版本号常量
│       ├── CubexDependencies.kt # 所有依赖坐标
│       ├── PluginMetadata.kt    # plugin.yml 生成助手(可选,第3阶段启用)
│       └── cubex-plugin.gradle.kts   # 约定插件(编译/shadow/runServer 一次写好)
├── modules/                     # 共享框架(第2阶段陆续建立)
│   ├── cubex-core/              # 必选:基类/生命周期/日志/消息颜色/资源清理
│   ├── cubex-config/            # 可选:配置加载合并
│   ├── cubex-i18n/              # 可选:多语言
│   ├── cubex-database/          # 可选:JDBC/HikariCP
│   └── cubex-command/           # 可选:命令/补全脚手架
├── BookLite/  EcoBalancer/  FAWEReplacer/  MountLicense/  Contracts/
├── RuleGems/  Metro/  Railway/  # 各插件原地成为 Gradle 子项目(目录名不变,保留历史)
└── (GigHub 为第三方 io.eliasnvx,暂不纳入;如纳入单独评估)
```

> `reference/` 是 **monorepo 根的兄弟目录**(在 `../reference`),天然不在 monorepo 内,无需 gitignore。

---

## 3. 命名与约定

- **group**:所有 CubeX 插件与模块统一 `org.cubexmc`(模块为 `org.cubexmc.core` 等)。
- **共享模块坐标**:`:modules:cubex-core`、`:modules:cubex-config`…(Gradle 路径)。
- **relocate 目标**:第三方库统一 relocate 到 `org.cubexmc.<plugin>.libs.<lib>`(沿用各插件 pom 现有约定,如 BookLite 的 `org.cubexmc.booklite.libs.gson`)。
- **产物 jar 名**:`archiveBaseName` 沿用各插件现名(如 `booklite`、`RuleGems`),`archiveClassifier=""`(无 `-all` 后缀)。
- **版本 tag(发布)**:带插件前缀,如 `rulegems-v1.0.9`、`metro-v1.2.0`、`railway-v0.4.1`。
- **测试服 MC 版本**:`runServer` 默认用 `1.20.1`(仅本地调试用,与各插件 `api-version` 下限无关)。

---

## 4. 技术栈基线(固定值,写进 `CubexVersions.kt`)

| 项 | 值 | 说明 |
|----|----|------|
| Gradle | 8.8(wrapper) | 与参考仓一致 |
| 构建脚本语言 | Kotlin DSL(`.kts`) | 源码仍是 Java,构建脚本用 Kotlin DSL 是常规做法 |
| 编译 JDK(toolchain) | 21 | 开发/编译用 |
| 字节码目标 | 17 | `options.release = 17`,运行时兼容 |
| shadow 插件 | `com.gradleup.shadow` 8.3.7 | |
| run-paper 插件 | `xyz.jpenilla.run-paper` 2.3.1 | 本地测试服 |
| 库版本 | 见 §6.3,按各插件**现状**填写 | 第1阶段不升级 |

---

## 5. 阶段总览

| 阶段 | 目标 | 产出 | 验收门 |
|------|------|------|--------|
| **0** | Gradle 地基 | `buildSrc`+根脚本+settings;**BookLite 用 Gradle 跑通** | BookLite `:BookLite:build` 成功、jar 等价、`runServer` 起得来 |
| **1** | 逐插件迁构建(代码不动) | 8 插件全部 Gradle 化,删除各 `pom.xml` | 每插件产物与 Maven 版等价;`./gradlew build` 全绿 |
| **2** | 设计 `cubex-core` + 可选模块 | `modules/*` 框架 + API;BookLite 试点接入 | BookLite 接入后行为不变;模块单测通过 |
| **3** | 逐插件接入 core(小→大) | 各插件去重接入;Metro/Railway 独立化 | 每插件行为不变;通用工具下沉到 `cubex-*` |
| **4** | CI + 发布镜像 | GH Actions 矩阵构建 + subtree split + release | CI 全绿;镜像 repo 收到代码与 release |

---

## 6. 第 0 阶段:Gradle 地基(含可直接落地的骨架)

### 6.0 步骤
1. 在 monorepo 根生成 Gradle wrapper(8.8):若无 gradle 命令,可从参考仓复制 `gradlew`/`gradlew.bat`/`gradle/wrapper/`。
2. 创建 §6.1~§6.7 的文件。
3. 仅先在 `settings.gradle.kts` 纳入 `:BookLite`(其余插件第1阶段再逐个加),并为 BookLite 写 §6.7 的 `build.gradle.kts`。
4. 跑验收门 §6.8。

### 6.1 `settings.gradle.kts`
```kotlin
rootProject.name = "CubeX-Plugins"
gradle.startParameter.isParallelProjectExecutionEnabled = true

// —— 共享模块(第2阶段陆续解除注释)——
// include(":modules:cubex-core"); project(":modules:cubex-core").projectDir = file("modules/cubex-core")

// —— 插件子项目(目录原名,原地成为子项目)——
// 第0阶段先只纳入 BookLite;第1阶段把其余插件逐个加进这个列表。
listOf("BookLite").forEach {
    include(":$it"); project(":$it").projectDir = file(it)
}
```

### 6.2 `gradle.properties`
```properties
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configureondemand=true
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
```

### 6.3 `buildSrc/build.gradle.kts`
```kotlin
plugins { `kotlin-dsl` }
repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
dependencies {
    implementation("com.gradleup.shadow:shadow-gradle-plugin:8.3.7")
    implementation("xyz.jpenilla.run-paper:xyz.jpenilla.run-paper.gradle.plugin:2.3.1")
}
```

### 6.4 `buildSrc/src/main/kotlin/CubexVersions.kt`
```kotlin
object CubexVersions {
    const val developmentJdk = 21
    const val targetJdk = 17
    const val shadow = "8.3.7"

    // —— 库版本:第1阶段按各插件现状填写,不擅自升级 ——
    const val sqliteJdbc = "3.49.1.0"   // 注:BookLite/RuleGems 当前为 3.45.3.0;统一为低风险候选,需单独测试后再做
    const val gson       = "2.11.0"
    const val hikariCP   = "6.3.0"
    // 测试
    const val junit   = "5.11.4"
    const val mockito = "5.11.0"
}
```

### 6.5 `buildSrc/src/main/kotlin/CubexDependencies.kt`
```kotlin
object CubexDeps {
    // —— 内置/shade 进 jar 的库 ——
    const val sqliteJdbc = "org.xerial:sqlite-jdbc:${CubexVersions.sqliteJdbc}"
    const val gson       = "com.google.code.gson:gson:${CubexVersions.gson}"
    const val hikariCP   = "com.zaxxer:HikariCP:${CubexVersions.hikariCP}"

    // —— provided / compileOnly 的服务器与第三方 API(version 由各插件传入,保留其下限)——
    fun spigotApi(v: String) = "org.spigotmc:spigot-api:$v"
    fun paperApi(v: String)  = "io.papermc.paper:paper-api:$v"
    const val vault          = "com.github.MilkBowl:VaultAPI:1.7.1"
    const val placeholderApi = "me.clip:placeholderapi:2.11.6"
    // WorldEdit / LuckPerms 等按需补充(坐标见各插件原 pom)

    // —— 测试 ——
    const val junitJupiter = "org.junit.jupiter:junit-jupiter:${CubexVersions.junit}"
    const val mockitoCore  = "org.mockito:mockito-core:${CubexVersions.mockito}"
}
```

### 6.6 `buildSrc/src/main/kotlin/cubex-plugin.gradle.kts`(约定插件 — 核心)
```kotlin
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
}

group = "org.cubexmc"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://jitpack.io")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://maven.enginehub.org/repo/")          // WorldEdit/FAWE
    maven("https://ci.ender.zone/plugin/repository/everything/") // Vault(部分)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(CubexVersions.developmentJdk)) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(CubexVersions.targetJdk)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")          // 无 -all 后缀,shadowJar 即部署产物
    mergeServiceFiles()                // 保留 META-INF/services(JDBC driver 等)
    exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
            "META-INF/maven/**", "module-info.class", "META-INF/versions/*/module-info.class")

    // —— 护栏#4:sqlite 原生库瘦身(只留 MC 服务器常见平台)——
    listOf(
        "org/sqlite/native/Mac/**", "org/sqlite/native/FreeBSD/**", "org/sqlite/native/Linux-Android/**",
        "org/sqlite/native/Linux/x86/**", "org/sqlite/native/Linux/arm/**", "org/sqlite/native/Linux/armv6/**",
        "org/sqlite/native/Linux/armv7/**", "org/sqlite/native/Linux/ppc64/**", "org/sqlite/native/Linux/riscv64/**",
        "org/sqlite/native/Linux-Musl/x86/**", "org/sqlite/native/Windows/x86/**",
        "org/sqlite/native/Windows/armv7/**", "org/sqlite/native/Windows/aarch64/**"
    ).forEach { exclude(it) }
    // ⚠️ 护栏#5:绝不 relocate sqlite-jdbc
}

// 部署产物 = shadowJar
tasks.named("build") { dependsOn("shadowJar") }
tasks.named<Jar>("jar") { archiveClassifier.set("plain") } // 避免与 shadowJar 输出名冲突

// 本地测试服
tasks.runServer { minecraftVersion("1.20.1") }
```

### 6.7 BookLite 的 `build.gradle.kts`(试点样例)
> 对照 `BookLite/pom.xml` 翻译。BookLite:spigot-api 1.18.2(provided)、sqlite-jdbc 3.45、gson(relocate 到 `org.cubexmc.booklite.libs.gson`)、slf4j-nop、junit/mockito(test)。
```kotlin
plugins { id("cubex-plugin") }

version = "0.1.0"
description = "BookLite"

dependencies {
    compileOnly(CubexDeps.spigotApi("1.18.2-R0.1-SNAPSHOT"))
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")   // 护栏#6:保留 BookLite 现有版本,不升级
    implementation(CubexDeps.gson)
    implementation("org.slf4j:slf4j-nop:1.7.36")
    testImplementation(CubexDeps.junitJupiter)
    testImplementation(CubexDeps.mockitoCore)
}

tasks.shadowJar {
    archiveBaseName.set("booklite")
    relocate("com.google.gson", "org.cubexmc.booklite.libs.gson")  // 沿用原 pom relocate
}

// plugin.yml 的 ${version} 占位由资源过滤注入(与原 Maven filtering 行为一致)
tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
```
> ⚠️ 若该插件 `plugin.yml` 此前不含 `${...}` 占位,`expand` 会因遇到无法解析的 `$` 报错——届时改用 `filteringCharset` + 仅替换已知占位,或保持 `plugin.yml` 原样不过滤。**目标:生成的 `plugin.yml` 与 Maven 产物逐字节等价(version 除外)。**

### 6.8 第 0 阶段验收门(全绿才算完成)
```bash
# 1) 构建成功
./gradlew :BookLite:clean :BookLite:build
# 2) 产物存在且无 -all 后缀
ls BookLite/build/libs/booklite-0.1.0.jar
# 3) 与 Maven 产物等价(先用 Maven 也打一份做对照)
unzip -l BookLite/build/libs/booklite-0.1.0.jar | awk '{print $4}' | sort > /tmp/gradle.txt
# (Maven: cd BookLite && mvn -q package; unzip -l target/booklite-0.1.0.jar ... > /tmp/maven.txt)
diff /tmp/maven.txt /tmp/gradle.txt   # 期望差异仅:sqlite 原生库平台(双方都已过滤则一致)、META 无关项
# 4) plugin.yml 正确生成
unzip -p BookLite/build/libs/booklite-0.1.0.jar plugin.yml | head
# 5) 冒烟:本地测试服能起、插件能 enable
./gradlew :BookLite:runServer   # 观察日志无异常,Ctrl-C 退出
```
**通过标准**:1~5 全部满足,且 jar 大小未异常膨胀(BookLite shaded ≈ 3.4M)。

---

## 7. 第 1 阶段:逐插件 Maven→Gradle 迁移(代码不动)

### 7.0 前置(开始迁移前必做一次):补根 `build.gradle.kts`
第 0 阶段只有 BookLite,可无根脚本;但本阶段起需要聚合任务(`§7.5` 会用 `shadowJarAll`)。
在 monorepo 根创建 `build.gradle.kts`:
```kotlin
// CubeX-Plugins 根构建脚本:仅定义聚合任务;子项目配置由 cubex-plugin 约定插件负责
group = "org.cubexmc"

// 构建所有插件子项目的部署 jar(modules 无 shadowJar,排除之)
tasks.register("shadowJarAll") {
    group = "build"; description = "构建所有插件子项目的 shadowJar"
    dependsOn(subprojects.filterNot { it.path.startsWith(":modules") }.map { "${it.path}:shadowJar" })
}
tasks.register("buildAllPlugins") {
    group = "build"; description = "构建所有子项目"
    dependsOn(subprojects.map { "${it.path}:build" })
}
tasks.register("cleanAll") {
    group = "build"; description = "清理所有子项目"
    dependsOn(subprojects.map { "${it.path}:clean" })
}
```
> 验收:`./gradlew tasks` 能看到 `shadowJarAll`/`buildAllPlugins`/`cleanAll`;`./gradlew :BookLite:build` 仍正常。

### 7.1 迁移顺序(由简到繁)
`FAWEReplacer(7)` → `MountLicense(34)` → `Contracts(40)` → `EcoBalancer(46)` → `RuleGems(119)` → `Metro(185)` → `Railway(242)`。(BookLite 已在第0阶段完成。)

### 7.2 单插件迁移检查清单(每个插件逐项做)
1. [ ] 在 `settings.gradle.kts` 的列表里加入该插件名。
2. [ ] 阅读其 `pom.xml`,按 §7.3 映射表写 `build.gradle.kts`。
3. [ ] 迁移 shade 配置:`relocations`、`filters`(尤其 sqlite 过滤器由约定插件统一提供,**不要重复写**)、`finalName`/`archiveBaseName`。
4. [ ] 迁移资源过滤:Maven `<filtering>true` → Gradle `processResources` + `expand`(仅对含占位的文件,通常是 `plugin.yml`)。
5. [ ] 保留该插件**现有依赖版本与 `api-version`**(护栏#3、#6)。
6. [ ] 删除 `pom.xml`(及 `target/` 已被 gitignore)。若有 gradle 子模块(Contracts/Metro 旧的)按实际情况并入或独立子项目。
7. [ ] 跑 §7.4 等价性验收门。

### 7.3 Maven → Gradle 映射表
| Maven | Gradle |
|-------|--------|
| `<scope>provided` / spigot/paper/vault/PAPI/WorldEdit API | `compileOnly(...)` |
| 默认 scope(compile,需打进 jar) | `implementation(...)` |
| `<scope>runtime` | `runtimeOnly(...)` |
| `<scope>test` | `testImplementation(...)` |
| `maven-shade-plugin` `relocation` | `tasks.shadowJar { relocate("a","b") }` |
| shade `filters`(sqlite 原生库) | **由约定插件统一处理,勿重复** |
| `<finalName>` / shade 输出名 | `tasks.shadowJar { archiveBaseName.set(...) }` |
| `<build><resources><filtering>` | `tasks.processResources { filesMatching(...) { expand(...) } }` |
| `maven-compiler-plugin` source/target | 约定插件已设 release=17,**勿重复** |

### 7.4 等价性验收门(每插件)
```bash
# 旧产物(迁移前先用 Maven 打一份留底):mvn -q -DskipTests package → target/<plugin>.jar
# 新产物:
./gradlew :<Plugin>:clean :<Plugin>:build
# A) 内容清单对比(期望仅无关差异)
diff <(unzip -l target/<plugin>.jar       | awk '{print $4}' | sort) \
     <(unzip -l <Plugin>/build/libs/<plugin>.jar | awk '{print $4}' | sort)
# B) plugin.yml 逐字节对比(version 除外)
diff <(unzip -p target/<plugin>.jar plugin.yml) \
     <(unzip -p <Plugin>/build/libs/<plugin>.jar plugin.yml)
# C) 冒烟:./gradlew :<Plugin>:runServer 起服无异常
```
**通过标准**:A 仅无关差异;B 仅 version 行差异;C 起服 enable 正常;jar 大小合理。

### 7.5 第 1 阶段整体验收门
```bash
./gradlew clean build        # 全部插件一次性构建通过
./gradlew shadowJarAll       # (根脚本提供的聚合任务)产出全部部署 jar
```
所有 `pom.xml` 已删除,`./gradlew build` 全绿。

### 7.6 第 1 阶段收尾:清理遗留物 + 初始化 monorepo(已定方案)
> 背景:迁移是在 8 个独立 repo 里**原地、未提交**完成的;`plugins/` 根尚非 git 仓库。
> 各插件**现在无法单独构建**(依赖根 `buildSrc`),故"迁移+合并"作为一个整体收尾。

**合并方案(用户已定)**:
- `plugins/` 根 = **新建开发 monorepo**(全新初始提交,不把历史 subtree 进根)。
- **8 个插件的 GitHub 远程 repo 全部保留**(不删不归档):历史完整留在各远程,继续作
  Spigot/Modrinth 指向与 wiki 门面;Phase 4 由 monorepo 经 `git subtree split` 向其推代码(只读镜像)。
- 本地各插件目录的嵌套 `.git` 移除(折叠进 monorepo);**远程 repo 不受影响**。

**遗留物处置(用户已定:全部移到 `../reference/` 作学习材料,不进 monorepo)**:
- `Contracts/KartaPlayerContract/`(独立 Kotlin 插件 `com.minekarta.karta`)→ `../reference/`
- `EcoBalancer/QuickTax/`(第三方 `tk.taverncraft.quicktax`)→ `../reference/`
- `Metro/Plugins/`(NewNanCity 参考仓的整份误放拷贝)→ `../reference/`(注:与 `reference/NewNanCity-Plugins` 内容重复,可改为直接删除)

**anvilgui**:Contracts 保留 `1.10.13-SNAPSHOT`(用户已定);事后需验证 anvil GUI 功能正常。

**SOP(逐步,带 stop point)**:
```bash
cd "C:/Users/Angus/Desktop/MC server/plugins"
# 1) 移走遗留物(先确认 reference 目录存在)
#    git mv 不适用(跨 repo),用文件系统移动;移动前确认这些目录无未推送的本地提交
mv Contracts/KartaPlayerContract ../reference/KartaPlayerContract
mv EcoBalancer/QuickTax          ../reference/QuickTax
rm -rf Metro/Plugins             # 与 reference/NewNanCity-Plugins 重复,直接删(或 mv 至 ../reference/Metro-Plugins-copy)

# 2) 【stop point】对每个插件 repo,确认没有"未推送的本地提交"会随 .git 删除而丢失:
for d in BookLite FAWEReplacer MountLicense Contracts EcoBalancer RuleGems Metro Railway; do
  echo "== $d =="; git -C "$d" log --oneline @{u}..HEAD 2>/dev/null || echo "(无上游或无法比较——人工确认)"
done
#    若有未推送提交 → 先 push 到各自远程(保留独立 repo 的完整历史),再继续。

# 3) 移除嵌套 .git(远程 repo 不受影响),让插件目录并入 monorepo
for d in BookLite FAWEReplacer MountLicense Contracts EcoBalancer RuleGems Metro Railway; do rm -rf "$d/.git"; done

# 4) 初始化 monorepo 并提交
git init
git add -A
./gradlew shadowJarAll          # 提交前再确认整体可构建(Windows 用 .\gradlew.bat)
git commit -m "chore: 初始化 CubeX-Plugins monorepo(Gradle 化 8 插件 + buildSrc 约定插件)"
```
**验收**:`plugins/` 是 git 仓库且工作区干净;`./gradlew shadowJarAll` 产出 8 jar;
8 个插件远程 repo 仍存在且未被改动;3 个遗留物已移出 monorepo。

---

## 8. 第 2 阶段:设计 `cubex-core` 与可选模块

> 这是**唯一需要"设计"的阶段**,不要机械照搬。先定 API 契约,再小步实现,先在 BookLite 验证。

### 8.1 模块与职责(从实测重复抽取,见提案 §8)
| 模块 | 职责 | 抽取来源(实测重复) |
|------|------|---------------------|
| `cubex-core`(必选) | `CubexPlugin` 基类、生命周期、前缀日志、消息/颜色工具、资源自动清理 | 各插件 `onEnable/onDisable`、ChatColor/MiniMessage(69 文件)、Metro/Railway 的 `ColorUtil`/`SchedulerUtil`/`LocationUtil`/`OwnershipUtil` |
| `cubex-config`(可选) | 配置加载/默认值合并/重载 | `saveDefaultConfig/getConfig`(60 文件)、Metro/Railway `ConfigFacade`/`ConfigUpdater` |
| `cubex-i18n`(可选) | 多语言:`lang/*.yml` 加载、`{0}` 格式化、玩家语言 | 各插件 `lang/en_US.yml`+`zh_CN.yml` |
| `cubex-database`(可选) | JDBC/HikariCP 连接封装 | 手写连接(14 文件) |
| `cubex-command`(可选) | 命令/Tab 补全注册脚手架 | `CommandExecutor/TabCompleter`(39 文件)、Metro/Railway `CommandRegistration` |

### 8.2 `cubex-core` API 契约(草案,第2阶段细化定稿)
```java
package org.cubexmc.core;

// 资源自动清理(Java 版 Terminable;基于 AutoCloseable)
public interface TerminableConsumer { <T extends AutoCloseable> T bind(T terminable); }

public abstract class CubexPlugin extends JavaPlugin implements TerminableConsumer {
    // onEnable/onDisable 由基类托管:统一异常捕获 + 资源关闭
    @Override public final void onEnable()  { /* 调 onPluginEnable();异常则安全 disable */ }
    @Override public final void onDisable() { /* 关闭所有 bind 的资源;调 onPluginDisable() */ }

    protected abstract void onPluginEnable();
    protected void onPluginDisable() {}
    public abstract void reloadPlugin();        // 各插件实现:清缓存、重载配置与语言

    // 便捷设施(由 core 提供)
    protected final CubexLogger log();          // 带插件前缀的日志
    protected final Messager messager();        // 颜色/MiniMessage 发送
    // 可选模块经各自 manager 暴露,使用懒加载 + bind(this) 绑定生命周期
}
```
**约定**(照搬提案"两条硬规范"):
- **四层单向依赖**:基础层→逻辑层(Manager/调度)→事务层(事件/命令/服务)→主类;上层调下层,下层不调上层。
- **Manager 懒加载 + 生命周期绑定**:`private final XxxManager m = bind(new XxxManager(this));` 或等价的懒加载;杜绝裸 `static`/未绑定资源。

### 8.3 模块构建
- 每个 `modules/cubex-*` 用约定插件的"库变体"(无需 `plugin.yml`/`runServer`,可只 apply `java`)。建议加一个轻量 `cubex-lib.gradle.kts` 约定(只配编译/测试),或直接在模块 `build.gradle.kts` 里 apply `java`。
- 在 `settings.gradle.kts` 解除对应 `include(":modules:cubex-*")` 注释。

### 8.4 验收门
- `./gradlew :modules:cubex-core:build` 通过,核心工具有单测(AAA 结构)。
- BookLite 改为 `extends CubexPlugin` 后:`runServer` 行为与接入前一致;`plugin.yml`/命令/权限不变。

---

## 9. 第 3 阶段:逐插件接入 core + Metro/Railway 独立化

### 9.1 接入顺序
小→大:`BookLite`(试点已做)→ `FAWEReplacer` → `MountLicense` → `Contracts` → `EcoBalancer` → `RuleGems` → `Metro` → `Railway`。

### 9.2 每插件接入步骤
1. 主类改为 `extends CubexPlugin`,把散落的生命周期/日志/配置/i18n 改用 core/可选模块。
2. 删除被 `cubex-*` 取代的重复代码(原 util/config loader 等)。
3. 在 `build.gradle.kts` 加 `implementation(project(":modules:cubex-core"))`(及所需可选模块)。
4. 在 `shadowJar` 把 `org.cubexmc.core`(及第三方库)relocate 到 `org.cubexmc.<plugin>.libs.*`(护栏#5:**不含 sqlite**)。
5. 跑等价性验收门(§7.4 的 A/B/C),**行为必须不变**。

### 9.3 Metro / Railway 专项(见提案 §8 决策)
- 二者做成**两个完全独立插件**,各保留全部领域代码(Stop/Line/GUI/物理/发车)。
- **取消 fork-merge**:删除 Railway 对 Metro 的 `upstream` 关系;此后各自演进。
- 仅把**插件无关的通用工具**下沉到 `cubex-*`:`ColorUtil`/`SchedulerUtil`/`LocationUtil`/`OwnershipUtil`→core,`ConfigFacade`/`ConfigUpdater`→config,命令脚手架→command,`lang/*.yml`→i18n。
- **领域代码不强行合并**(Stop/MetroAPI/PortalManager 已各自重写,合并风险高、收益低)。
- 二者各自独立 `version`/`tag`/jar。

---

## 10. 第 4 阶段:CI + 发布镜像

### 10.1 CI(GitHub Actions,矩阵构建)
- 矩阵:对每个插件 `./gradlew :<Plugin>:build`;PR 必跑。
- 缓存 Gradle;JDK 21。

### 10.2 按 tag 发布
- 推送 `rulegems-v*` 触发只构建 RuleGems → 上传 jar 到 Release + Spigot + Modrinth。

### 10.3 发布镜像(per-plugin 只读 repo)
- 用 `git subtree split` 或 `splitsh/lite` 把 `plugins/<Name>/` 历史推到对应独立 repo(见 §11)。
- 镜像 repo 保留 README/wiki/issues/releases,继续作 Spigot/Modrinth 指向目标;**不在镜像构建**(缺共享 `cubex-*` 源码)。

---

## 11. 仓库合并 SOP(`git subtree`,保留历史)

> URL 与默认分支已核实(`git ls-remote --symref <url> HEAD`,2026-05-31)。**注意非常规项**:
> FAWEReplacer 默认分支是 `master`;Railway 是 `railway`;Contracts 的仓库名是 `Contract`(单数);
> EcoBalancer 在个人账号 `angushushu`(非 `CubeX-MC` 组织),远程默认分支 `main`(本地副本可能在 `shu` 分支)。
```bash
# 1) 新建 monorepo(或就用现 plugins 目录初始化)
cd "C:/Users/Angus/Desktop/MC server/plugins"
git init && git commit --allow-empty -m "chore: init CubeX monorepo"

# 2) 逐个导入(历史落到子目录)—— prefix=本地目录名,URL/分支=已核实值
git subtree add --prefix=BookLite     https://github.com/CubeX-MC/BookLite.git        main
git subtree add --prefix=EcoBalancer  https://github.com/angushushu/EcoBalancer.git   main
git subtree add --prefix=FAWEReplacer https://github.com/CubeX-MC/FAWEReplacer.git     master
git subtree add --prefix=MountLicense https://github.com/CubeX-MC/MountLicense.git     main
git subtree add --prefix=Contracts    https://github.com/CubeX-MC/Contract.git         main
git subtree add --prefix=RuleGems     https://github.com/CubeX-MC/RuleGems.git         main
git subtree add --prefix=Metro        https://github.com/CubeX-MC/Metro.git            main
git subtree add --prefix=Railway      https://github.com/CubeX-MC/Railway.git          railway
# 导入后:删除各子目录里残留的嵌套 .git(若有);target/、.idea/ 等已由根 .gitignore 覆盖
# (EcoBalancer 若想导入 shu 分支而非 main,把末尾 main 换成 shu;请先确认哪个是想要的基线)

# 3) 回推镜像(发布时,prefix/URL/分支按上表对应;以 BookLite / Railway 为例)
git subtree split --prefix=BookLite -b split/booklite
git push https://github.com/CubeX-MC/BookLite.git split/booklite:main      # 或推到 mirror 分支
git subtree split --prefix=Railway  -b split/railway
git push https://github.com/CubeX-MC/Railway.git  split/railway:railway
```

**各插件远程速查表:**

| 目录(prefix) | 远程 URL | 默认分支 |
|---------------|----------|---------|
| BookLite | `https://github.com/CubeX-MC/BookLite.git` | `main` |
| EcoBalancer | `https://github.com/angushushu/EcoBalancer.git` ⚠️个人账号 | `main` |
| FAWEReplacer | `https://github.com/CubeX-MC/FAWEReplacer.git` | `master` ⚠️ |
| MountLicense | `https://github.com/CubeX-MC/MountLicense.git` | `main` |
| Contracts | `https://github.com/CubeX-MC/Contract.git` ⚠️仓库名单数 | `main` |
| RuleGems | `https://github.com/CubeX-MC/RuleGems.git` | `main` |
| Metro | `https://github.com/CubeX-MC/Metro.git` | `main` |
| Railway | `https://github.com/CubeX-MC/Railway.git` | `railway` ⚠️ |
> 备选:历史价值不需要时也可"全新开始"(直接用现有工作副本,不做 subtree),但提案 #1 已定**保留历史**。

---

## 12. 通用验证门(每个子任务/PR 都要过)
- [ ] `./gradlew :<目标>:build` 成功,无 warning 升级为 error 的遗漏。
- [ ] 受影响插件 jar:`unzip -l` 内容合理、大小未异常膨胀、sqlite 仅含保留平台。
- [ ] `plugin.yml`、命令、权限节点与改动前等价(除非本任务明确变更)。
- [ ] `runServer` 起服、目标插件 enable 无异常堆栈。
- [ ] 贴出上述命令的关键输出作为证据。

---

## 13. 风险与回滚
- **每阶段独立 commit/PR**,便于回滚;迁移某插件失败时,该插件可暂留 Maven(保留其 `pom.xml`),不阻塞其他插件。
- **保留 Maven 产物留底**用于等价性对比,直到该插件 Gradle 化验收通过再删 `pom.xml`。
- **沙箱/测试服先行**:任何接入 core 的改动先在 `runServer` 验证,再考虑上生产。
- **GigHub** 为第三方(`io.eliasnvx`),默认不纳入本次重构;如纳入需单独评估其 Kotlin 多模块结构。

---

## 14. 给执行 Agent 的总纲(TL;DR)
1. 读本文件 + 护栏(§1)。按 §5 阶段顺序推进,**逐阶段过验收门**。
2. 第 0 阶段先把 §6 的骨架落地、BookLite 跑通——这是一切的可运行起点。
3. 第 1 阶段纯换构建、**零行为变更**,用 §7.4 等价性门把关。
4. 第 2 阶段才设计 `cubex-core`(§8 契约),小步实现、BookLite 先验证。
5. 第 3 阶段逐插件接入并去重;Metro/Railway 独立化(§9.3)。
6. 第 4 阶段上 CI 与发布镜像。
7. 任何不确定 → 回查 `ARCHITECTURE_PROPOSAL.md` 的对应决策;仍不明确则停下来问,**不要擅自改变玩法或统一各插件 MC 版本**。
```
