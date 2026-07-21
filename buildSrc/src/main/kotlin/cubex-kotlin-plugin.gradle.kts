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
    // Keep the lightweight reflection interfaces shipped by kotlin-stdlib
    // (KClass/KFunction/etc.); Kotlin callable references require them even
    // when the full kotlin-reflect implementation is not used.
    exclude("kotlin/reflect/full/**")
    exclude("kotlin/reflect/jvm/**")
    relocate("kotlin", "${CubexRelocations.libsNamespace(project.name)}.kotlin")
}
