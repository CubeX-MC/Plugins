import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.attributes.java.TargetJvmVersion

plugins { id("cubex-kotlin-plugin") }

version = "0.1.0"
description = "Regions"

// The joint R1 dev server consumes Contract's shadowJar task provider. Configuration-on-demand
// otherwise leaves Contract unevaluated when :Regions:runServer is the entry task.
val runWithContract = providers.gradleProperty("regionsRunWithContract")
    .map(String::toBoolean)
    .getOrElse(true)
if (runWithContract) evaluationDependsOn(":Contract")

listOf("compileClasspath", "testCompileClasspath", "testRuntimeClasspath").forEach { name ->
    configurations.named(name).configure {
        attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
    }
}

dependencies {
    compileOnly(CubexDeps.paperApi("1.21.11-R0.1-SNAPSHOT"))
    implementation(project(":modules:cubex-core"))
    implementation(project(":modules:cubex-config"))
    implementation(project(":modules:cubex-i18n"))
    implementation(project(":modules:cubex-scheduler"))
    implementation(project(":modules:cubex-integrations"))
    // ChatInputState:聊天提问状态机与两条聊天链路的去重(PLAN §7.4)
    implementation(project(":modules:cubex-gui"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation(CubexDeps.mockitoCore)
}

tasks.shadowJar {
    archiveBaseName.set("regions")
    relocate("com.tcoded.folialib", "${CubexRelocations.libsNamespace(project.name)}.folialib")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("project" to mapOf("version" to project.version))
    }
}

tasks.runServer {
    minecraftVersion("1.21.11")
    runDirectory(file(if (runWithContract) "run" else "run-no-contract"))
    // R1 fault-injection harness: Regions stays compile-independent from Contract, but the dev
    // server receives Contract's deployable jar plus the same Vault stack Contract uses itself.
    if (runWithContract) {
        pluginJars(project(":Contract").tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
    }
    downloadPlugins {
        github("MilkBowl", "Vault", "1.7.3", "Vault.jar")
        github("EssentialsX", "Essentials", "2.20.1", "EssentialsX-2.20.1.jar")
    }
}
