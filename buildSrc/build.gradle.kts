plugins { `kotlin-dsl` }

repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation("com.gradleup.shadow:shadow-gradle-plugin:8.3.7")
    implementation("xyz.jpenilla.run-paper:xyz.jpenilla.run-paper.gradle.plugin:2.3.1")
}
