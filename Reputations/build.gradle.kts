plugins { id("cubex-kotlin-plugin") }

version = "1.0.0"
description = "Reputations — shared player reputation service (Vault-style API)"

dependencies {
    compileOnly(CubexDeps.spigotApi("1.18.2-R0.1-SNAPSHOT"))
    // PlaceholderAPI is optional. Exclude its Adventure copy so it cannot change the server API
    // version selected for this Spigot-targeted plugin.
    compileOnly(CubexDeps.placeholderApi) { exclude(group = "net.kyori") }
    implementation(project(":modules:cubex-core"))
    implementation("org.bstats:bstats-bukkit:3.1.0")
    testImplementation(CubexDeps.junitJupiter)
}

tasks.shadowJar {
    archiveBaseName.set("Reputations")
    relocate("org.bstats", "org.cubexmc.reputations.libs.bstats")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("project" to mapOf("version" to project.version))
    }
}

tasks.runServer {
    // The plugin must also work without PlaceholderAPI; that standalone path is covered before
    // enabling this richer default dev-server setup (see REAL_SERVER_TEST.md).
    minecraftVersion("1.20.1")
    downloadPlugins {
        url("https://repo.extendedclip.com/content/repositories/placeholderapi/me/clip/placeholderapi/2.11.6/placeholderapi-2.11.6.jar")
    }
}
