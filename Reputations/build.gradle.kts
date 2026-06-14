plugins { id("cubex-kotlin-plugin") }

version = "1.0.0"
description = "Reputations — shared player reputation service (Vault-style API)"

dependencies {
    compileOnly(CubexDeps.spigotApi("1.18.2-R0.1-SNAPSHOT"))
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
