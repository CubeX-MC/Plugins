plugins {
    `java-library`
}

group = "org.cubexmc"
version = "0.1.0"
description = "CubeX shared scheduler facade"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://repo.tcoded.com/releases")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(CubexVersions.developmentJdk)) }
}

dependencies {
    api(project(":modules:cubex-core"))
    compileOnly(CubexDeps.spigotApi("1.18.2-R0.1-SNAPSHOT"))
    implementation("com.tcoded:FoliaLib:0.5.1")
    testImplementation(CubexDeps.junitJupiter)
    testImplementation(CubexDeps.mockitoCore)
    testImplementation(CubexDeps.spigotApi("1.18.2-R0.1-SNAPSHOT"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(CubexVersions.targetJdk)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
