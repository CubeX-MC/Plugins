import com.github.jengelman.gradle.plugins.shadow.relocation.SimpleRelocator
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.zip.ZipFile

plugins {
    java
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
}

group = "org.cubexmc"

// —— 打包模式(硬约束见 AGENTS.md,路线见 PLAN.md §7.1)——
// 默认 EMBEDDED = 现状:cubex-* 与 Kotlin stdlib shade + relocate 进自己的 jar。
// 插件要改模式就在自己的 build 脚本里写 cubex { packaging.set(CubexPackagingMode.EXTERNAL) }。
val cubex = extensions.create<CubexPluginExtension>("cubex")
cubex.packaging.convention(CubexPackagingMode.EMBEDDED)

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://jitpack.io")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://mvn.wesjd.net/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://ci.ender.zone/plugin/repository/everything/")
    maven("https://repo.megavex.net/maven/")
    maven("https://repo.megavex.net/snapshots/")
    maven("https://repo.bluecolored.de/releases/")
    maven("https://repo.mikeprimm.com/")
    maven("https://repo.tcoded.com/releases")
}

configurations {
    testCompileOnly {
        extendsFrom(compileOnly.get())
    }
    testRuntimeOnly {
        extendsFrom(compileOnly.get())
    }
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
    archiveClassifier.set("")
    mergeServiceFiles()
    exclude(
        "META-INF/MANIFEST.MF",
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/maven/**",
        "META-INF/proguard/**",
        "module-info.class",
        "META-INF/versions/*/module-info.class",
    )

    // —— 护栏#4:sqlite 原生库瘦身(只留 MC 服务器常见平台)——
    listOf(
        "org/sqlite/native/Mac/**",
        "org/sqlite/native/FreeBSD/**",
        "org/sqlite/native/Linux-Android/**",
        "org/sqlite/native/Linux/x86/**",
        "org/sqlite/native/Linux/arm/**",
        "org/sqlite/native/Linux/armv6/**",
        "org/sqlite/native/Linux/armv7/**",
        "org/sqlite/native/Linux/ppc64/**",
        "org/sqlite/native/Linux/riscv64/**",
        "org/sqlite/native/Linux-Musl/x86/**",
        "org/sqlite/native/Windows/x86/**",
        "org/sqlite/native/Windows/armv7/**",
        "org/sqlite/native/Windows/aarch64/**",
    ).forEach { exclude(it) }
    // 护栏#5:绝不 relocate sqlite-jdbc

    if (cubex.packaging.get() == CubexPackagingMode.EMBEDDED) {
        CubexModules.embeddedRelocations(project.name).forEach { (source, target) ->
            // Shadow matches raw prefixes: command must not capture RuleGems' commands package.
            relocate(source, target) { include(source.replace('.', '/') + "/**") }
        }
    }

    // EXTERNAL:cubex-*、Kotlin stdlib、FoliaLib 都由 CubeXLib 在运行时提供,不进 jar。
    // Adventure 仍是各插件自己的决定(Paper 提供 / Spigot 需自带),这里不替它们决定。
    if (cubex.packaging.get() == CubexPackagingMode.EXTERNAL) {
        CubexModules.archivePatterns.forEach { exclude(it) }
        exclude("kotlin/**")
        exclude("com/tcoded/**")
    }
}

// EXTERNAL 模式的 depend 由构建注入,**不要手写**:手写必然与实际打包模式漂移,
// 而且症状是启动期 UnknownDependencyException,不是编译期报错。
tasks.processResources {
    val packaging = cubex.packaging
    // 打包模式必须是任务输入:否则改了模式而资源文件没变时 processResources 会 UP-TO-DATE,
    // doLast 不跑,jar 里留着上一次模式的 plugin.yml。
    inputs.property("cubexPackaging", packaging)
    doLast {
        if (packaging.get() != CubexPackagingMode.EXTERNAL) return@doLast
        val yml = destinationDir.resolve("plugin.yml")
        if (!yml.isFile) return@doLast
        yml.writeText(CubexPluginYml.withDepend(yml.readText(), CubexModules.LIB_PLUGIN_NAME))
    }
}

// 部署产物 = shadowJar
tasks.named("build") { dependsOn("shadowJar") }
tasks.named<Jar>("jar") { archiveClassifier.set("plain") }

// 本地测试服
tasks.runServer { minecraftVersion("1.20.1") }

// —— 部署 jar 门禁(Kotlin 迁移验收,见 KOTLIN_STYLE_GUIDE.md 的 Jar Gate 一节)——
// 已 opt-in Kotlin 的插件:stdlib 必须 relocate、不得残留 kotlin/**、不得打入 kotlin-reflect 实现。
// 未 opt-in 的插件:jar 里不得出现任何 Kotlin runtime。
// 两者共同:本仓库自己的类必须是 targetJdk 字节码,且 plugin.yml 必须在。
tasks.register("jarGate") {
    group = "verification"
    description = "校验部署 jar 的 Kotlin runtime relocate、字节码版本与 plugin.yml"
    dependsOn(tasks.named("shadowJar"))

    val jarFile = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile }
    val projectName = project.name
    val isKotlinPlugin = project.plugins.hasPlugin("org.jetbrains.kotlin.jvm")
    // 允许的字节码版本:插件自己的 java release(Clarity 覆盖成 21),Kotlin 侧固定 jvmTarget。
    // cubex-* 共享模块保持全仓 Java 17 基线,shade 进 Java 21 的 Clarity 时仍是兼容字节码。
    val javaRelease = tasks.named<JavaCompile>("compileJava").map { it.options.release.orNull ?: CubexVersions.targetJdk }
    val sharedModuleMajor = CubexVersions.targetJdk + 44
    // 新增共享模块只改 CubexModules.archivePrefixes 一处。漏登记的模块会被拿"插件自己的
    // java release"去校验字节码:Clarity(release 21) 一旦接入就会被误判为失败。
    val sharedModulePrefixes = CubexModules.archivePrefixes
    val packaging = cubex.packaging
    // relocate 目标命名空间下的类是第三方库(字节码版本各异),不参与本仓库自有类的版本校验
    val shadedPaths = tasks.named<ShadowJar>("shadowJar").map { shadow ->
        shadow.relocators
            .filterIsInstance<SimpleRelocator>()
            .map { it.shadedPattern.replace('.', '/').trimEnd('/') + "/" }
    }

    inputs.file(jarFile)

    doLast {
        val libsPrefix = CubexRelocations.libsNamespace(projectName).replace('.', '/')
        val pluginMajor = javaRelease.get() + 44
        val pluginAllowedMajors = setOf(pluginMajor)
        val relocatedModulePrefixes = CubexModules.relocatedArchivePrefixes(projectName)
        val allModulePrefixes = sharedModulePrefixes + relocatedModulePrefixes
        val failures = mutableListOf<String>()
        val report = mutableListOf<String>()

        ZipFile(jarFile.get().asFile).use { zip ->
            val entries = zip.entries().toList()
            val names = entries.map { it.name }

            val unrelocatedKotlin = names.count { it.startsWith("kotlin/") }
            val relocatedKotlin = names.count { it.startsWith("$libsPrefix/kotlin/") }
            val reflectImpl = names.count { it.contains("kotlin/reflect/full/") || it.contains("kotlin/reflect/jvm/") }

            val cubexModuleEntries = names.count { name -> sharedModulePrefixes.any(name::startsWith) }
            val relocatedModuleEntries = names.count { name -> relocatedModulePrefixes.any(name::startsWith) }
            val unexpectedSharedClasses = names.filter {
                CubexJarClasses.isUnexpectedSharedClass(it, "$libsPrefix/cubex/", relocatedModulePrefixes)
            }
            if (unexpectedSharedClasses.isNotEmpty()) {
                failures += "共享包重定位误包含未登记的业务包: " + unexpectedSharedClasses.take(5).joinToString()
            }
            val mode = packaging.get()

            report += "mode=$mode unrelocatedKotlin=$unrelocatedKotlin relocatedKotlin=$relocatedKotlin " +
                "reflectImpl=$reflectImpl cubexModuleEntries=$cubexModuleEntries relocatedModuleEntries=$relocatedModuleEntries"

            val duplicates = names.filter { it.endsWith(".class") }.groupingBy { it }.eachCount().filterValues { it > 1 }
            if (duplicates.isNotEmpty()) failures += "jar 内出现重复类: " + duplicates.keys.take(5).joinToString()

            // 三种模式共同要求
            if (reflectImpl > 0) {
                failures += "jar 内出现 $reflectImpl 个 kotlin-reflect 实现类(kotlin/reflect/{full,jvm})"
            }
            if (names.none { it == "plugin.yml" }) {
                failures += "jar 内缺少 plugin.yml"
            }

            when (mode) {
                // 自包含:stdlib 必须 relocate,不得残留 kotlin/**
                CubexPackagingMode.EMBEDDED -> {
                    if (cubexModuleEntries > 0) {
                        failures += "EMBEDDED 模式残留 $cubexModuleEntries 个未 relocate 的 cubex-* 条目"
                    }
                    if (unrelocatedKotlin > 0) {
                        failures += "jar 内残留 $unrelocatedKotlin 个未 relocate 的 kotlin/** 条目"
                    }
                    if (isKotlinPlugin && relocatedKotlin == 0) {
                        failures += "已 opt-in Kotlin 但 jar 内没有 $libsPrefix/kotlin/**,stdlib 没被打进来"
                    }
                    if (!isKotlinPlugin && relocatedKotlin > 0) {
                        failures += "未 opt-in Kotlin 的插件却打入了 $relocatedKotlin 个 Kotlin runtime 条目"
                    }
                }

                // 外置:cubex-* 与 Kotlin runtime 都由 CubeXLib 提供,jar 里一个都不许有
                CubexPackagingMode.EXTERNAL -> {
                    if (unrelocatedKotlin + relocatedKotlin > 0) {
                        failures += "EXTERNAL 模式不得携带 Kotlin runtime(由 ${CubexModules.LIB_PLUGIN_NAME} 提供)," +
                            "实际 unrelocated=$unrelocatedKotlin relocated=$relocatedKotlin"
                    }
                    if (cubexModuleEntries + relocatedModuleEntries > 0) {
                        failures += "EXTERNAL 模式不得打入 cubex-* 模块类(由 ${CubexModules.LIB_PLUGIN_NAME} 提供)," +
                            "实际 raw=$cubexModuleEntries relocated=$relocatedModuleEntries 个条目"
                    }
                    val yml = zip.getEntry("plugin.yml")
                        ?.let { entry -> zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) } }
                        .orEmpty()
                    if (!yml.contains(CubexModules.LIB_PLUGIN_NAME)) {
                        failures += "EXTERNAL 模式的 plugin.yml 缺少 depend: [${CubexModules.LIB_PLUGIN_NAME}]" +
                            "(应由 processResources 注入,不要手写)"
                    }
                }

                // CubeXLib 自己:全仓唯一携带未 relocate stdlib 的 jar,且必须装齐全部共享模块
                CubexPackagingMode.LIB -> {
                    if (projectName != CubexModules.LIB_PLUGIN_NAME) {
                        failures += "LIB 模式全仓只允许 ${CubexModules.LIB_PLUGIN_NAME} 使用,当前是 $projectName"
                    }
                    if (unrelocatedKotlin == 0) {
                        failures += "LIB 模式必须携带未 relocate 的 Kotlin stdlib —— 它是全仓唯一的提供方"
                    }
                    val missing = sharedModulePrefixes.filter { prefix -> names.none { it.startsWith(prefix) } }
                    if (missing.isNotEmpty()) {
                        failures += "LIB jar 缺少共享模块:" + missing.joinToString(", ")
                    }
                }
            }

            // 本仓库自己的类:org/cubexmc/** 里排除所有 relocate 目标命名空间
            val shaded = shadedPaths.get()
            val ownClasses = entries.filter { entry ->
                CubexJarClasses.expectedMajor(entry.name, pluginMajor, sharedModuleMajor, allModulePrefixes, shaded) != null
            }
            val wrongBytecode = ownClasses.mapNotNull { entry ->
                val header = ByteArray(8)
                val read = zip.getInputStream(entry).use { it.readNBytes(header, 0, 8) }
                if (read < 8) {
                    return@mapNotNull "${entry.name}(读不到字节码头)"
                }
                val major = ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
                val allowedMajors = setOf(
                    requireNotNull(CubexJarClasses.expectedMajor(entry.name, pluginMajor, sharedModuleMajor, allModulePrefixes, shaded)),
                )
                if (major in allowedMajors) null else "${entry.name}(major=$major, allowed=${allowedMajors.sorted()})"
            }
            report += "ownClasses=${ownClasses.size} pluginBytecodeMajors=${pluginAllowedMajors.sorted()} sharedBytecodeMajor=$sharedModuleMajor"
            if (wrongBytecode.isNotEmpty()) {
                failures += "字节码版本不符的类 ${wrongBytecode.size} 个:" + wrongBytecode.take(5).joinToString(", ")
            }
        }

        logger.lifecycle("[jarGate] $projectName kotlinOptIn=$isKotlinPlugin ${report.joinToString(" | ")}")
        if (failures.isNotEmpty()) {
            throw GradleException("[jarGate] $projectName 未通过:\n  - " + failures.joinToString("\n  - "))
        }
    }
}
