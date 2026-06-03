plugins { id("cubex-plugin") }

version = "1.2.1"
description = "EcoBalancer"

dependencies {
    compileOnly(CubexDeps.spigotApi("1.21.1-R0.1-SNAPSHOT"))
    compileOnly("dev.folia:folia-api:1.20.1-R0.1-SNAPSHOT")
    compileOnly("net.milkbowl.vault:VaultAPI:1.7")
    implementation(project(":modules:cubex-core"))
    implementation(project(":modules:cubex-config"))
    implementation(project(":modules:cubex-i18n"))
    implementation(project(":modules:cubex-scheduler"))
    implementation(platform("net.kyori:adventure-bom:4.25.0"))
    implementation("net.kyori:adventure-api")
    implementation("net.kyori:adventure-text-minimessage")
    implementation("net.kyori:adventure-text-serializer-legacy")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    compileOnly(CubexDeps.placeholderApi)
    testImplementation(CubexDeps.junitJupiter)
    testImplementation(CubexDeps.mockitoCore)
}

tasks.shadowJar {
    archiveBaseName.set("EcoBalancer")
    relocate("net.kyori", "org.cubexmc.ecobalancer.libs.kyori")
    relocate("com.tcoded.folialib", "org.cubexmc.ecobalancer.libs.folialib")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("project" to mapOf("version" to project.version))
    }
}

tasks.runServer {
    downloadPlugins {
        github("MilkBowl", "Vault", "1.7.3", "Vault.jar")
        github("EssentialsX", "Essentials", "2.20.1", "EssentialsX-2.20.1.jar")
    }
}
