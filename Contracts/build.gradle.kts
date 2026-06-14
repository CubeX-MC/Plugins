import org.gradle.api.attributes.java.TargetJvmVersion

plugins { id("cubex-kotlin-plugin") }

version = "0.1.0"
description = "Contract"

// Contracts targets Paper: the server provides Adventure (so we no longer shade/relocate it) and the
// 1.21.6+ Dialog API. paper-api's Gradle metadata pins JVM 21 for resolution, but we still emit Java
// 17 bytecode (release 17, inherited) so the plugin loads on Paper 1.18+; the Dialog adapter is
// runtime-guarded (DialogSupport) and only classloads on 1.21.6+. Raise just the compile-time
// resolution ceiling so the Paper artifact resolves.
listOf("compileClasspath", "testCompileClasspath", "testRuntimeClasspath").forEach { name ->
    configurations.named(name).configure {
        attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
    }
}

dependencies {
    compileOnly(CubexDeps.paperApi("1.21.11-R0.1-SNAPSHOT"))
    compileOnly(CubexDeps.vault)
    implementation(project(":modules:cubex-core"))
    implementation(project(":modules:cubex-config"))
    implementation(project(":modules:cubex-i18n"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation(CubexDeps.mockitoCore)
}

tasks.shadowJar {
    archiveBaseName.set("contract")
    dependencies {
        include(project(":modules:cubex-core"))
        include(project(":modules:cubex-config"))
        include(project(":modules:cubex-i18n"))
        include(dependency("org.jetbrains.kotlin:.*:.*"))
    }
    // Adventure (net.kyori) is provided by the Paper server at runtime; do not bundle or relocate it —
    // the Dialog API exchanges the server's own Adventure types with us.
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("project" to mapOf("version" to project.version))
    }
}

tasks.runServer {
    // 1.21.6+ so the Paper Dialog API path is exercised in the dev server (older builds fall back to chat).
    minecraftVersion("1.21.8")
    downloadPlugins {
        github("MilkBowl", "Vault", "1.7.3", "Vault.jar")
        github("EssentialsX", "Essentials", "2.20.1", "EssentialsX-2.20.1.jar")
    }
}
