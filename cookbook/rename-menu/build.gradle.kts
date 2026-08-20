plugins { id("cubex-kotlin-plugin") }

version = "1.0.0"
description = "Cookbook 05 — 改名菜单:Menu + fillEmpty + ChatInputState"

cubex { packaging.set(CubexPackagingMode.EXTERNAL) }

dependencies {
    compileOnly(CubexDeps.spigotApi("1.18.2-R0.1-SNAPSHOT"))
    implementation(project(":modules:cubex-core"))
    implementation(project(":modules:cubex-gui"))
    implementation(project(":modules:cubex-scheduler"))
    testImplementation(CubexDeps.junitJupiter)
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.shadowJar { archiveBaseName.set("CookbookRenameMenu") }
