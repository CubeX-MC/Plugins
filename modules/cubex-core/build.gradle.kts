plugins {
    java
}

group = "org.cubexmc"
version = "0.1.0"
description = "CubeX shared core"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://oss.sonatype.org/content/groups/public/")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(CubexVersions.developmentJdk)) }
}

dependencies {
    compileOnly(CubexDeps.spigotApi("1.18.2-R0.1-SNAPSHOT"))
    testImplementation(CubexDeps.junitJupiter)
    testImplementation(CubexDeps.spigotApi("1.18.2-R0.1-SNAPSHOT"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(CubexVersions.targetJdk)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
