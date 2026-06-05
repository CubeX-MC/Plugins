plugins { id("cubex-kotlin-plugin") }

version = "1.0.4"
description = "FAWEReplace"

dependencies {
    compileOnly(CubexDeps.paperApi("1.20.2-R0.1-SNAPSHOT"))
    compileOnly("com.sk89q.worldedit:worldedit-core:7.2.17")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.2.17")
    implementation(project(":modules:cubex-core"))
    implementation(project(":modules:cubex-config"))
    implementation(project(":modules:cubex-i18n"))
    implementation("org.bstats:bstats-bukkit:3.1.0")
    testImplementation(CubexDeps.junitJupiter)
    testImplementation(CubexDeps.mockitoCore)
    testImplementation(CubexDeps.paperApi("1.20.2-R0.1-SNAPSHOT"))
}

tasks.shadowJar {
    archiveBaseName.set("FAWEReplace")
    relocate("net.kyori", "org.cubexmc.fawereplace.libs.kyori")
    relocate("org.bstats", "org.cubexmc.fawereplace.bstats")
}

tasks.runServer {
    downloadPlugins {
        url("https://mediafilez.forgecdn.net/files/4793/142/worldedit-bukkit-7.2.17.jar")
    }
}
