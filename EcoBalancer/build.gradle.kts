plugins { id("cubex-plugin") }

version = "1.2.1"
description = "EcoBalancer"

dependencies {
    compileOnly(CubexDeps.spigotApi("1.21.1-R0.1-SNAPSHOT"))
    compileOnly("dev.folia:folia-api:1.20.1-R0.1-SNAPSHOT")
    compileOnly("net.milkbowl.vault:VaultAPI:1.7")
    implementation(project(":modules:cubex-core"))
    implementation(project(":modules:cubex-scheduler"))
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    compileOnly(CubexDeps.placeholderApi)
    testImplementation(CubexDeps.junitJupiter)
    testImplementation(CubexDeps.mockitoCore)
}

tasks.shadowJar {
    archiveBaseName.set("EcoBalancer")
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
