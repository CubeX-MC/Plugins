import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("cubex-plugin")
    kotlin("jvm")
}

kotlin {
    jvmToolchain(CubexVersions.developmentJdk)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(CubexVersions.targetJdk.toString()))
        javaParameters.set(true)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.named<ShadowJar>("shadowJar") {
    exclude("kotlin/reflect/**")
    relocate("kotlin", "${CubexRelocations.libsNamespace(project.name)}.kotlin")
}
