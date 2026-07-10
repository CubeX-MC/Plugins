import org.gradle.api.attributes.java.TargetJvmVersion

plugins { id("cubex-kotlin-plugin") }

version = "0.1.0"
description = "Regions"

listOf("compileClasspath", "testCompileClasspath", "testRuntimeClasspath").forEach { name ->
    configurations.named(name).configure {
        attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
    }
}

dependencies {
    compileOnly(CubexDeps.paperApi("1.21.11-R0.1-SNAPSHOT"))
    compileOnly(CubexDeps.vault)
    compileOnly(CubexDeps.placeholderApi)
    implementation(project(":modules:cubex-core"))
    implementation(project(":modules:cubex-config"))
    implementation(project(":modules:cubex-i18n"))
    implementation(project(":modules:cubex-scheduler"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation(CubexDeps.mockitoCore)
}

tasks.shadowJar {
    archiveBaseName.set("regions")
    dependencies {
        include(project(":modules:cubex-core"))
        include(project(":modules:cubex-config"))
        include(project(":modules:cubex-i18n"))
        include(project(":modules:cubex-scheduler"))
        include(dependency("com.tcoded:FoliaLib:.*"))
        include(dependency("org.jetbrains.kotlin:.*:.*"))
    }
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("project" to mapOf("version" to project.version))
    }
}

tasks.runServer {
    minecraftVersion("1.21.8")
}
