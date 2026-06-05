import java.util.Properties

plugins { `kotlin-dsl` }

repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    val kotlinVersion = providers.gradleProperty("kotlinVersion").orElse(provider {
        val properties = Properties()
        rootDir.resolve("../gradle.properties").inputStream().use(properties::load)
        properties.getProperty("kotlinVersion")
    }).get()

    implementation("com.gradleup.shadow:shadow-gradle-plugin:8.3.7")
    implementation("xyz.jpenilla.run-paper:xyz.jpenilla.run-paper.gradle.plugin:2.3.1")
    implementation("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:$kotlinVersion")
}
