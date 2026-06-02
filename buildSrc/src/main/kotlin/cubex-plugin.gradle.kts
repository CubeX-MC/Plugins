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
