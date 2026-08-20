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
    implementation("xyz.jpenilla.run-paper:xyz.jpenilla.run-paper.gradle.plugin:3.0.0")
    implementation("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:$kotlinVersion")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// plugin.yml 的 depend 注入是构建关键逻辑,必须有测试兜着。
tasks.withType<Test>().configureEach { useJUnitPlatform() }
