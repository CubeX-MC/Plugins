plugins { id("cubex-plugin") }

version = "0.1.0"
description = "Contract"

dependencies {
    compileOnly(CubexDeps.spigotApi("1.18.2-R0.1-SNAPSHOT"))
    compileOnly(CubexDeps.vault)
    implementation("net.wesjd:anvilgui:1.10.13-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.shadowJar {
    archiveBaseName.set("contract")
    dependencies {
        include(dependency("net.wesjd:anvilgui:.*"))
    }
    relocate("net.wesjd.anvilgui", "org.cubexmc.contract.lib.anvilgui")
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
