plugins { id("cubex-kotlin-plugin") }

version = "1.0.0"
description = "Cookbook 01 — 外置模式最小插件"

// 外置模式:不 shade 任何 cubex-*,运行时由 CubeXLib 以原包名提供。
// plugin.yml 的 depend: [CubeXLib] 由构建注入,**不要手写**(见 AGENTS.md 硬约束)。
cubex { packaging.set(CubexPackagingMode.EXTERNAL) }

dependencies {
    compileOnly(CubexDeps.spigotApi("1.18.2-R0.1-SNAPSHOT"))

    // 写法与内嵌模式**完全一样** —— 编译期不区分模式,只有打包不同。
    implementation(project(":modules:cubex-core"))
    implementation(project(":modules:cubex-config"))

    testImplementation(CubexDeps.junitJupiter)
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.shadowJar { archiveBaseName.set("CookbookHelloExternal") }
