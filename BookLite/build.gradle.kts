plugins { id("cubex-kotlin-plugin") }

version = "0.1.0"
description = "BookLite"

dependencies {
    compileOnly(CubexDeps.spigotApi("1.18.2-R0.1-SNAPSHOT"))
    implementation(project(":modules:cubex-core"))
    implementation(project(":modules:cubex-config"))
    implementation(project(":modules:cubex-i18n"))
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    implementation(CubexDeps.gson)
    implementation("org.slf4j:slf4j-nop:1.7.36")
    testImplementation(CubexDeps.junitJupiter)
    testImplementation(CubexDeps.mockitoCore)
}

tasks.shadowJar {
    archiveBaseName.set("booklite")
    relocate("com.google.gson", "org.cubexmc.booklite.libs.gson")
    relocate("net.kyori", "org.cubexmc.booklite.libs.kyori")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("project" to mapOf("version" to project.version))
    }
}
