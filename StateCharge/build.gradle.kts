import org.gradle.api.attributes.java.TargetJvmVersion

plugins { id("cubex-kotlin-plugin") }

version = "0.1.0"
description = "StateCharge — paid timed player states (shrink / grow / fly) over Vault"

// StateCharge targets Paper 1.20.5+: the scale states use the modern Attribute.SCALE attribute API
// (the same route Clarity takes; paper-api 1.21.11 no longer ships Entity#setScale), so there is no
// ProtocolLib dependency. paper-api's Gradle metadata pins JVM 21 for resolution, but we still emit
// Java 17 bytecode (release 17, inherited) like the rest of the repo.
listOf("compileClasspath", "testCompileClasspath", "testRuntimeClasspath").forEach { name ->
    configurations.named(name).configure {
        attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
    }
}

dependencies {
    compileOnly(CubexDeps.paperApi("1.21.11-R0.1-SNAPSHOT"))
    compileOnly(CubexDeps.vault)
    implementation(project(":modules:cubex-core"))
    implementation(project(":modules:cubex-scheduler"))
    implementation(project(":modules:cubex-config"))
    implementation(project(":modules:cubex-i18n"))
    // 交易页:Menu/ItemBuilder/fillEmpty + 保险阈值的聊天输入(ChatInputState)
    implementation(project(":modules:cubex-gui"))
    implementation("com.tcoded:FoliaLib:0.5.1")
    testImplementation(CubexDeps.junitJupiter)
    testImplementation(CubexDeps.mockitoCore)
}

tasks.shadowJar {
    archiveBaseName.set("statecharge")
    // Adventure (net.kyori) is provided by the Paper server at runtime, so it stays unshaded.
    exclude("net/kyori/**")
    relocate("com.tcoded.folialib", "${CubexRelocations.libsNamespace(project.name)}.folialib")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("project" to mapOf("version" to project.version))
    }
}

tasks.runServer {
    // 1.21.2+ so the native Entity#setScale API path is exercised in the dev server.
    minecraftVersion("1.21.4")
    downloadPlugins {
        github("MilkBowl", "Vault", "1.7.3", "Vault.jar")
        github("EssentialsX", "Essentials", "2.20.1", "EssentialsX-2.20.1.jar")
    }
}
