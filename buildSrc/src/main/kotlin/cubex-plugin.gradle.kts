import com.github.jengelman.gradle.plugins.shadow.relocation.SimpleRelocator
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.zip.ZipFile

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
    val kotlinMajor = CubexVersions.targetJdk + 44
    val sharedModulePrefixes = listOf(
        "org/cubexmc/core/",
        "org/cubexmc/config/",
        "org/cubexmc/i18n/",
        "org/cubexmc/scheduler/",
    )
    // relocate 目标命名空间下的类是第三方库(字节码版本各异),不参与本仓库自有类的版本校验
    val shadedPaths = tasks.named<ShadowJar>("shadowJar").map { shadow ->
        shadow.relocators
            .filterIsInstance<SimpleRelocator>()
            .map { it.shadedPattern.replace('.', '/').trimEnd('/') + "/" }
    }

    inputs.file(jarFile)

    doLast {
        val libsPrefix = CubexRelocations.libsNamespace(projectName).replace('.', '/')
        val pluginAllowedMajors =
            if (isKotlinPlugin) setOf(javaRelease.get() + 44, kotlinMajor) else setOf(javaRelease.get() + 44)
        val failures = mutableListOf<String>()
        val report = mutableListOf<String>()

        ZipFile(jarFile.get().asFile).use { zip ->
            val entries = zip.entries().toList()
            val names = entries.map { it.name }

            val unrelocatedKotlin = names.count { it.startsWith("kotlin/") }
            val relocatedKotlin = names.count { it.startsWith("$libsPrefix/kotlin/") }
            val reflectImpl = names.count { it.contains("kotlin/reflect/full/") || it.contains("kotlin/reflect/jvm/") }

            report += "unrelocatedKotlin=$unrelocatedKotlin relocatedKotlin=$relocatedKotlin reflectImpl=$reflectImpl"

            if (unrelocatedKotlin > 0) {
                failures += "jar 内残留 $unrelocatedKotlin 个未 relocate 的 kotlin/** 条目"
            }
            if (reflectImpl > 0) {
                failures += "jar 内出现 $reflectImpl 个 kotlin-reflect 实现类(kotlin/reflect/{full,jvm})"
            }
            if (isKotlinPlugin && relocatedKotlin == 0) {
                failures += "已 opt-in Kotlin 但 jar 内没有 $libsPrefix/kotlin/**,stdlib 没被打进来"
            }
            if (!isKotlinPlugin && relocatedKotlin > 0) {
                failures += "未 opt-in Kotlin 的插件却打入了 $relocatedKotlin 个 Kotlin runtime 条目"
            }
            if (names.none { it == "plugin.yml" }) {
                failures += "jar 内缺少 plugin.yml"
            }

            // 本仓库自己的类:org/cubexmc/** 里排除所有 relocate 目标命名空间
            val shaded = shadedPaths.get()
            val ownClasses = entries.filter { entry ->
                entry.name.startsWith("org/cubexmc/") &&
                    entry.name.endsWith(".class") &&
                    shaded.none { entry.name.startsWith(it) }
            }
            val wrongBytecode = ownClasses.mapNotNull { entry ->
                val header = ByteArray(8)
                val read = zip.getInputStream(entry).use { it.readNBytes(header, 0, 8) }
                if (read < 8) {
                    return@mapNotNull "${entry.name}(读不到字节码头)"
                }
                val major = ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
                val allowedMajors =
                    if (sharedModulePrefixes.any(entry.name::startsWith)) setOf(kotlinMajor) else pluginAllowedMajors
                if (major in allowedMajors) null else "${entry.name}(major=$major, allowed=${allowedMajors.sorted()})"
            }
            report += "ownClasses=${ownClasses.size} pluginBytecodeMajors=${pluginAllowedMajors.sorted()} sharedBytecodeMajor=$kotlinMajor"
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
