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

val javaRelease = tasks.named<JavaCompile>("compileJava").flatMap { it.options.release }

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(javaRelease.map { JvmTarget.fromTarget(it.toString()) })
        javaParameters.set(true)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

private val cubexPackaging = extensions.getByType<CubexPluginExtension>().packaging

tasks.named<ShadowJar>("shadowJar") {
    // Keep the lightweight reflection interfaces shipped by kotlin-stdlib
    // (KClass/KFunction/etc.); Kotlin callable references require them even
    // when the full kotlin-reflect implementation is not used.
    exclude("kotlin/reflect/full/**")
    exclude("kotlin/reflect/jvm/**")
    // 只有 EMBEDDED 才 relocate。EXTERNAL/LIB 下 stdlib 由 CubeXLib 以**原包名**提供 ——
    // 一旦 relocate,两侧的 kotlin.* 就成了不同的类,跨插件调用必然 NoClassDefFoundError。
    if (cubexPackaging.get() == CubexPackagingMode.EMBEDDED) {
        relocate("kotlin", "${CubexRelocations.libsNamespace(project.name)}.kotlin")
    }
}
