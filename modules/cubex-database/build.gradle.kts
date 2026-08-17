plugins { id("cubex-kotlin-library") }

group = "org.cubexmc"
version = "0.1.0"
description = "CubeX SQLite connection factory and PRAGMA configuration"

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
    // sqlite-jdbc stays compileOnly here: each plugin already ships (and never relocates) its own
    // copy, and the module must not add a second one to their shaded jars.
    compileOnly(CubexDeps.sqliteJdbc)
    testImplementation(CubexDeps.junitJupiter)
    testImplementation(CubexDeps.mockitoCore)
    testImplementation(CubexDeps.spigotApi("1.18.2-R0.1-SNAPSHOT"))
    testRuntimeOnly(CubexDeps.sqliteJdbc)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(CubexVersions.targetJdk)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
