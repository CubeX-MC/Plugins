plugins { id("cubex-kotlin-library") }

group = "org.cubexmc"
version = "0.1.0"
description = "CubeX Vault economy: charge routing to a configured account (player or Vault bank)"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://oss.sonatype.org/content/groups/public/")
    // VaultAPI 只在 jitpack 上;其余模块不需要这个仓库,所以只在这里加。
    maven("https://jitpack.io")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(CubexVersions.developmentJdk)) }
}

dependencies {
    api(project(":modules:cubex-core"))
    compileOnly(CubexDeps.spigotApi("1.18.2-R0.1-SNAPSHOT"))
    // Vault 由服务器提供;和 sqlite-jdbc 的取舍不同,这个从来不打进插件 jar。
    compileOnly(CubexDeps.vault)
    testImplementation(CubexDeps.junitJupiter)
    testImplementation(CubexDeps.mockitoCore)
    testImplementation(CubexDeps.spigotApi("1.18.2-R0.1-SNAPSHOT"))
    testImplementation(CubexDeps.vault)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(CubexVersions.targetJdk)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
