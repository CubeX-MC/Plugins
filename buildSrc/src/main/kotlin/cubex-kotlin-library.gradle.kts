import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `java-library`
    kotlin("jvm")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(CubexVersions.developmentJdk)) }
}

kotlin {
    jvmToolchain(CubexVersions.developmentJdk)
    // 共享模块的每个公开声明都要写明可见性与返回类型:不是为了对外契约(对内框架不需要),
    // 而是让读模块源码的队友和 agent 不必去推断签名。插件侧不开,那边的声明不是给别人调用的。
    explicitApi()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(CubexVersions.targetJdk)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(CubexVersions.targetJdk.toString()))
        javaParameters.set(true)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
