# CubeX Kotlin Migration Design

> 路线 C 目标:在现有 Gradle monorepo、Java cubex-* 共享模块、9 个 Java 插件全部可发布的基础上,把 Kotlin 作为增量语言能力引入。迁移必须逐插件、逐类推进,任意阶段都能构建、发布、回滚;禁止 big-bang。

## 1. 当前基线

- monorepo 已由 `cubex-plugin` 约定插件统一 Java toolchain:JDK 21 构建、Java 17 bytecode target。
- 共享模块为 `modules/cubex-core`、`modules/cubex-scheduler`、`modules/cubex-config`、`modules/cubex-i18n`,目前均为 Java。
- 插件源码仍是 Java,当前规模约为:

| 插件 | Java 文件数 | 迁移风险 |
| --- | ---: | --- |
| FAWEReplacer | 8 | 小,适合作为首个稳定试点 |
| Clarity | 8 | 小,但应等其当前业务基线稳定后再纳入 |
| BookLite | 13 | 小,已覆盖 config/i18n 现代化路径 |
| MountLicense | 25 | 小中,含 actionbar/list adapter |
| Contracts | 27 | 小中,含 Vault abort 与 enum label adapter |
| EcoBalancer | 44 | 中,含数据库、调度、真实配置迁移 |
| RuleGems | 97 | 中高,含 Cloud、Adventure、SQLite、scheduler |
| Metro | 115 | 高,含文本现代化、scoreboard、FoliaLib、领域逻辑 |
| Railway | 167 | 最高,Metro fork 加 tick/physics/entity 默认合并 |

## 2. 构建启用 Kotlin

### 2.1 设计原则

- Kotlin 是构建能力,不是一次性迁移开关。启用后 `.java` 与 `.kt` 可以共存、互调。
- 继续使用 JDK 21 toolchain 与 Java 17 bytecode target。
- 插件仍通过 `cubex-plugin` 约定插件构建;不要求每个插件重复配置 Kotlin。
- `cubex-core` 先保持 Java。Kotlin 插件调用 Java core 无摩擦;反向让 Java core 依赖 Kotlin API 会提前制造互操作负担。

### 2.2 buildSrc 改动草案

建议把 Kotlin 版本集中到 `gradle.properties`,再由 `buildSrc` 读取,避免 buildSrc 自身构建脚本无法可靠引用 `CubexVersions` 的鸡生蛋问题。

```properties
# gradle.properties
kotlinVersion=2.2.21
```

```kotlin
// buildSrc/build.gradle.kts
plugins {
    `kotlin-dsl`
}

val kotlinVersion: String by project

dependencies {
    implementation("com.gradleup.shadow:shadow-gradle-plugin:8.3.7")
    implementation("xyz.jpenilla.run-paper:xyz.jpenilla.run-paper.gradle.plugin:2.3.1")
    implementation("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:$kotlinVersion")
}
```

```kotlin
// buildSrc/src/main/kotlin/CubexVersions.kt
object CubexVersions {
    const val developmentJdk = 21
    const val targetJdk = 17
    const val kotlin = "2.2.21"
}
```

`cubex-plugin.gradle.kts` 草案:

```kotlin
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    kotlin("jvm")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(CubexVersions.developmentJdk))
    }
}

kotlin {
    jvmToolchain(CubexVersions.developmentJdk)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(CubexVersions.targetJdk)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(CubexVersions.targetJdk.toString()))
        javaParameters.set(true)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}
```

混编注意:

- Kotlin 可直接调用现有 Java cubex-* API。
- Java 调 Kotlin 时要避免默认参数、顶层函数、companion object 等 Kotlin 语法泄露成不直观的 JVM API。需要 Java 调用的 Kotlin API 应显式使用 `@JvmOverloads`、`@JvmStatic` 或普通 class 方法。
- 同一 source set 内 Java/Kotlin 互调可行,但迁移时不要制造 Java↔Kotlin 循环依赖。优先转换叶子类、DTO、纯工具,最后转换被大量 Java 类调用的中心类。

## 3. kotlin-stdlib 打包与 relocate

### 3.1 版本

路线 C 使用 Kotlin 2.x。设计示例固定为 `2.2.21`;真正落地时先检查官方 Kotlin release 说明与 Gradle/JDK 兼容性,确认后只通过一次显式依赖评审升级。

参考:官方 Kotlin release 页面 <https://kotlinlang.org/docs/releases.html>

### 3.2 打包策略

每个插件仍是独立可发布 jar,因此 Kotlin runtime 必须随插件 shade 进去,不能假设服务器已有 stdlib。

Relocate 规则与 Adventure/FoliaLib 一致:每个插件自己的 jar 内只有自己的 Kotlin runtime 命名空间。

```kotlin
tasks.named<ShadowJar>("shadowJar") {
    relocate("kotlin", "org.cubexmc.<plugin>.libs.kotlin")
}
```

`<plugin>` 不建议用 `project.name.lowercase()` 盲推,因为现有 relocate 命名已有约定差异,例如 `FAWEReplacer` 应使用 `fawereplace` 风格时就不能自动猜。建议在 buildSrc 增加一个中心映射:

```kotlin
object CubexRelocations {
    private val pluginIds = mapOf(
        "BookLite" to "booklite",
        "FAWEReplacer" to "fawereplace",
        "MountLicense" to "mountlicense",
        "Contracts" to "contract",
        "EcoBalancer" to "ecobalancer",
        "RuleGems" to "rulegems",
        "Metro" to "metro",
        "Railway" to "railway",
        "Clarity" to "clarity",
    )

    fun libsNamespace(projectName: String): String =
        "org.cubexmc.${pluginIds.getValue(projectName)}.libs"
}
```

```kotlin
tasks.named<ShadowJar>("shadowJar") {
    relocate("kotlin", "${CubexRelocations.libsNamespace(project.name)}.kotlin")
}
```

如果后续引入 `kotlinx-coroutines`、`kotlinx-serialization` 等,再单独加:

```kotlin
relocate("kotlinx", "${CubexRelocations.libsNamespace(project.name)}.kotlinx")
```

本路线不主动引入这些库。

### 3.3 stdlib 体积与 reflect

- `kotlin-stdlib` 预计给每个插件 jar 增加约 1.5MB 到 1.7MB。9 个插件总发布体积会增加约 13MB 到 16MB,但单插件独立发布模型下这是可接受成本。
- 默认不引入 `kotlin-reflect`。它体积更大,且在 shade/minimize/relocate 下更容易出现运行期缺类或 metadata 问题。
- 只有当某插件明确使用 `KClass.memberProperties`、运行期 Kotlin reflection、序列化框架或 DI 框架时,才允许显式引入 `kotlin-reflect`,并在该插件验收中证明 relocated 后正常。
- 继续不启用全局 `minimize()`。如果某插件局部使用 minimize,必须显式排除 Kotlin runtime 与 reflect。

### 3.4 与现有 relocated 库共存

- Kotlin relocate 不影响 `org.cubexmc.*` 自有代码。
- 保留现有 Adventure、FoliaLib、Cloud、scoreboard 等 relocate 规则。
- 保留 sqlite-jdbc 瘦身过滤器,仍绝不 relocate sqlite-jdbc。
- 验收必须检查无未 relocate 的 `kotlin/**` class 泄露。

## 4. 迁移顺序与粒度

### 4.1 阶段划分

1. C0:仅启用 Kotlin 构建能力,不转换源码。
2. C1:首个小插件试点,建议 FAWEReplacer。
3. C2:小插件逐个迁移,每个插件独立验收并提交。
4. C3:中型插件迁移。
5. C4:可选共享模块按调用压力迁移。
6. C5:Metro/Railway 这类重插件最后迁移。
7. C6:cubex-core 最后迁移,或长期保留 Java 也可接受。

### 4.2 推荐插件顺序

推荐顺序:

1. FAWEReplacer:文件最少,无数据库,业务面窄,适合验证构建、shade、runServer、WorldEdit compileOnly 不回退。
2. BookLite:小,且 config/i18n 现代化路径成熟。
3. MountLicense:验证 actionbar/list adapter 与较多命令路径。
4. Contracts:验证 Vault abort、enum label adapter、GUI/storage/task bind 不回退。
5. EcoBalancer:验证数据库、scheduler cancelAll、真实 ConfigMigrator 包装后的 Kotlin 互操作。
6. RuleGems:验证 Cloud、Adventure、SQLite、scheduler 与 Kotlin 共存。
7. Metro:大插件,等 Kotlin 工程规则稳定后迁移。
8. Railway:最后迁移,复用 Metro 经验但保留 tick/physics/entity 数据安全。
9. Clarity:如果它已经进入稳定发布线,可插在 FAWEReplacer 后;如果仍在快速变化,放到中后段避免与业务开发互相干扰。

### 4.3 共享模块时机

- `cubex-config`、`cubex-i18n`:在至少两个小插件 Kotlin 化后再考虑。它们是公共 API,迁移时优先保持 Java ABI 友好,不要暴露 Kotlin-only 调用方式。
- `cubex-scheduler`:等 EcoBalancer/RuleGems/Metro/Railway 至少两个 Kotlin 调用点跑通后再考虑。调度 API 的 Java 互操作和 lambda overload 要格外小心。
- `cubex-core`:最后迁移。`CubexPlugin`、`Terminable`、`Messager` 等是所有插件共同底座,提前 Kotlin 化会让 Java 插件反向调用 Kotlin API,收益低、风险高。

## 5. 转换方式

### 5.1 机械转换适用场景

适合先机械 `.java -> .kt`,再做少量手修:

- 主类与生命周期模板。
- Bukkit listener。
- command handler。
- repository/service 这类已有清晰 Java 结构的类。
- Metro/Railway 的 train/tick/physics 关键路径。

这些代码迁移目标是行为等价,不是一次性重写成 Kotlin 风格。

### 5.2 地道 Kotlin 适用场景

适合逐步改成 idiomatic Kotlin:

- 纯值对象、不可变 DTO、配置快照。
- 小型结果类型、枚举辅助、纯字符串/颜色转换工具。
- 无 Bukkit 生命周期副作用的纯函数。
- 测试 fixture builder。

可以使用 `data class`、sealed result、extension function,但要先确认不会改变:

- `equals/hashCode/toString` 语义。
- Bukkit/YAML/反射依赖的构造器与 getter 名称。
- Java 调用点需要的 overload。

### 5.3 暂不建议 Kotlin 化的写法

- 不引入 coroutine。现有 FoliaLib/cubex-scheduler 已承担调度抽象,coroutine 会改变取消、线程与异常语义。
- 不把配置/消息改成 Kotlin DSL。配置格式已经现代化,不需要再引入新的抽象层。
- 不把公共 Java API 改成 Kotlin 顶层函数。顶层函数会生成 `FooKt` facade,不利于 Java 插件或后续兼容。
- 不在大插件第一轮使用大量 `lateinit` 或 `!!` 掩盖可空性;Bukkit API 的 nullable 必须显式处理。

## 6. 验收标准

Kotlin 化属于源码级重写,jar class 清单不会与 Java 版 byte-for-byte 等价。因此验收标准从"内容等价"调整为"行为等价 + 公开面不变"。

每个插件迁移后必须通过:

```powershell
.\gradlew.bat :<Plugin>:build
.\gradlew.bat :<Plugin>:test
.\gradlew.bat :<Plugin>:runServer
.\gradlew.bat shadowJarAll
```

如果插件没有测试任务或测试为空,如实记录。

Jar 验收:

- `plugin.yml` 逐字节不变。
- commands、permissions、api-version、softdepend/depend 不变。
- bStats Metrics ID 不变。
- bundled 默认配置、语言、数据文件不变,除非该插件本轮明确要改资源格式。
- SQLite native 仍只保留既定 5 个目标平台。
- `sqlite-jdbc` 未被 relocate。
- `org/cubexmc/<plugin>/libs/kotlin/**` 存在。
- 未 relocate Kotlin class 数为 0,即 jar 内无 `kotlin/**`。
- 未意外引入 `kotlin/reflect/**`;若显式引入 reflect,必须列为偏差并证明运行正常。
- bytecode target 仍为 Java 17,可用 `javap -verbose` 抽查 Kotlin 编译出的 class major version 为 61。

行为验收:

- runServer enable 正常。
- reload 路径正常。
- RCON `stop` 触发 disable 无异常,资源清理不漏、不 double-close。
- 关键命令、GUI、title/actionbar、scoreboard、database、scheduler、迁移器路径按插件特点逐项冒烟。
- 对 Metro/Railway,额外观察 tick/physics/train lifecycle 不受 Kotlin 转换影响。

## 7. 主要风险与护栏

### 7.1 Kotlin/Java 互操作

风险:

- Java 平台类型导致 Kotlin 误判非空。
- Bukkit API 大量 nullable 返回值被 `!!` 放大成运行期崩溃。
- Java 调 Kotlin 默认参数不可见。
- companion object 方法未加 `@JvmStatic` 时 Java 调用点变形。
- Kotlin property getter/setter 名称与旧 Java 方法不一致。

护栏:

- 对 Bukkit、Vault、WorldEdit、Cloud、Adventure API 返回值默认按 nullable 处理。
- Java 仍会调用的 Kotlin 类保留原方法签名。
- 公共 API 不使用 Kotlin 默认参数作为唯一入口;需要时加 `@JvmOverloads`。
- 每次迁移先跑现有 Java 调用编译,再跑 runServer。

### 7.2 打包与 runtime

风险:

- 多插件各自 shade Kotlin runtime,jar 体积增加。
- Kotlin stdlib 未 relocate,与其它插件或服务端环境冲突。
- reflect/minimize 组合导致运行期缺类。
- Kotlin metadata 与 relocation/反射组合可能踩坑。

护栏:

- 每插件独立 relocate `kotlin -> org.cubexmc.<plugin>.libs.kotlin`。
- 默认不引入 reflect。
- 不启用全局 minimize。
- jar 检查 `unrelocatedKotlin=0`。

### 7.3 语义变化

风险:

- `data class` 自动生成 equality/copy 改变领域对象语义。
- Kotlin collection nullability 与 mutability 改变调用方预期。
- `use {}`、`also/apply` 等作用域函数改变关闭顺序或异常传播。
- Lambda overload 在 Bukkit scheduler/Cloud API 中选错重载。

护栏:

- 资源关闭顺序保持现有 cubex-core bind LIFO 语义。
- 大插件先机械迁移,不顺手重构领域模型。
- 对有 overload 的 Java API 显式标注参数类型。
- 对 public data/model 类转换为 `data class` 前必须有 equality 语义评估。

### 7.4 团队与调试

风险:

- 团队 Kotlin 熟悉度不均。
- Kotlin stack trace、synthetic class、default argument bridge 增加调试成本。
- 后续贡献者混用过度 idiomatic 写法。

护栏:

- 先制定轻量 Kotlin style guide:显式可空、少用 scope nesting、禁止 coroutine、公共 API Java-friendly。
- 每个插件迁移 PR 只做一个明确范围。
- 保留 Java 代码也可接受;路线 C 是允许混编,不是强制所有文件立刻 Kotlin。

## 8. 与 cubex-* 共享模块的关系

Kotlin 插件使用 Java cubex-* 不需要额外 adapter:

- `CubexPlugin`、`Terminable`、`Messager`、`CubexScheduler`、`I18nService`、`MigrationRunner` 都是普通 Java API。
- Kotlin 可以直接传 lambda 给 Java functional interface。
- `AutoCloseable`、`Runnable`、`Consumer` 互操作自然。

共享模块如果后续迁移为 Kotlin,必须反向保证 Java 插件仍可无痛调用:

- 保留原 package/class/method 名称。
- 避免把 public API 改为 top-level function 或 Kotlin-only inline/reified API。
- 对可选参数提供 `@JvmOverloads` 或显式 builder/options class。
- 对 companion object 工厂方法加 `@JvmStatic`。
- 继续单 public 类型单文件的清晰边界,不要把多个公共 facade 混到一个 Kotlin 文件里。

结论:路线 C 的最佳路径是先让插件 Kotlin 化,共享模块晚一点,Kotlin core 最后。这样每一步都能发布,也能随时停在 Java/Kotlin 混编的稳定状态。

## 9. 建议的首轮实施门

首轮只做 C0 + FAWEReplacer 试点:

1. 修改 buildSrc 启用 Kotlin JVM。
2. 不转换源码,先跑 `.\gradlew.bat shadowJarAll`,证明 Java-only 项目未受影响。
3. 转换 FAWEReplacer 的 1 到 2 个叶子类,加 stdlib relocate,跑完整验收。
4. 再转换 FAWEReplacer 主类与其余类。
5. 试点通过 review 后,沉淀 Kotlin style guide 与 jar 检查脚本,再推进其它插件。

这个顺序能先验证最关键的构建、relocate、运行期 stdlib 与 Java 17 bytecode,同时把业务风险控制在最小插件内。

## 10. Review 结论(API 把关人已确认,实施以此为准)

**设计稿通过**(relocate 中心映射、no-reflect、no-minimize、FAWEReplacer 先/core 最后、机械 vs 地道、行为等价验收、互操作风险护栏都对)。一处**必须修正** + 一处小项:

- **【必改】Kotlin 逐插件 opt-in,不在共享 `cubex-plugin` 全局启用**。§2.2 的"`cubex-plugin` 无条件 apply kotlin"会让**全部 9 插件 jar 立刻 +1.5MB stdlib(含未迁/纯 Java 插件)**,与 §9 step 2"Java-only 不受影响"矛盾,也违背"任意阶段最小可发布"。
  - 改为新增独立约定插件 **`cubex-kotlin-plugin`**:`plugins { id("cubex-plugin"); kotlin("jvm") }` + KotlinCompile 配置(jvmTarget 17/javaParameters/-Xjsr305=strict)+ stdlib relocate(`relocate("kotlin", "${CubexRelocations.libsNamespace(project.name)}.kotlin")`)。
  - 插件迁移时把 `id("cubex-plugin")` → `id("cubex-kotlin-plugin")` 一行 opt-in。`cubex-plugin` 本身保持纯 Java、不引入 kotlin/stdlib。
  - 效果:未迁/纯 Java 插件 jar **零变化**(§9 step 2 才真正成立);仅 Kotlin 化的插件打包 relocated stdlib。
- **【小项】Kotlin 版本去重**:`gradle.properties`(buildSrc 自身脚本用)与 `CubexVersions.kt` 两处都写了版本,注意保持同步或单一来源,避免漂移。
- 其余(stdlib relocate 策略、reflect/minimize 护栏、迁移顺序、转换取舍、验收标准、风险护栏、共享模块 Java-friendly、core 最后)全部照设计执行。

**首轮实施门(修正后)= C0 + FAWEReplacer 试点**:
1. buildSrc 加 kotlin-gradle-plugin 依赖 + 新增 `cubex-kotlin-plugin` 约定(不动 `cubex-plugin`)。
2. **先不改任何插件**,跑 `shadowJarAll`,**验证 9 个 Java 插件 jar 与之前 byte 级一致**(证明全局未受影响——这是 opt-in 的关键收益)。
3. FAWEReplacer 改用 `id("cubex-kotlin-plugin")`,转 1~2 个叶子类,加 stdlib relocate,过完整验收(jar 含 relocated kotlin、unrelocatedKotlin=0、bytecode major=61、plugin.yml/Metrics/sqlite 不变、runServer 行为一致)。
4. 再转 FAWEReplacer 其余类。
5. 试点通过后沉淀 Kotlin style guide + jar 检查脚本,再推其它插件。
