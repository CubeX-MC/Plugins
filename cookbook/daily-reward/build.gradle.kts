plugins { id("cubex-kotlin-plugin") }

version = "1.0.0"
description = "Cookbook 03 — 每日签到:onEvent + Cooldown"

cubex { packaging.set(CubexPackagingMode.EXTERNAL) }

dependencies {
    compileOnly(CubexDeps.spigotApi("1.18.2-R0.1-SNAPSHOT"))
    implementation(project(":modules:cubex-core"))
    testImplementation(CubexDeps.junitJupiter)
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.shadowJar { archiveBaseName.set("CookbookDailyReward") }
