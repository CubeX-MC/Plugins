plugins { id("cubex-kotlin-plugin") }

version = "0.1.0"
description = "CubeXLib — 外置模式插件的运行时:以原包名提供 cubex-* 共享模块"

// 全仓唯一的 LIB 模式项目(jarGate 会校验这一点)。
cubex { packaging.set(CubexPackagingMode.LIB) }

dependencies {
    compileOnly(CubexDeps.spigotApi("1.18.2-R0.1-SNAPSHOT"))

    // 9 个共享模块整包打进来,且**不 relocate** —— 外置模式插件在运行时按原包名解析。
    implementation(project(":modules:cubex-core"))
    implementation(project(":modules:cubex-config"))
    implementation(project(":modules:cubex-i18n"))
    implementation(project(":modules:cubex-scheduler"))
    implementation(project(":modules:cubex-integrations"))
    implementation(project(":modules:cubex-database"))
    implementation(project(":modules:cubex-command"))
    implementation(project(":modules:cubex-gui"))
    implementation(project(":modules:cubex-spatial"))

    // cubex-database 在模块里把 sqlite-jdbc 声明为 compileOnly(好让**内嵌**插件各自打包)。
    // 运行时提供方这一侧必须真的带一份,否则外置插件调 SQLiteDatabase 时会 NoClassDefFoundError。
    // 约定插件的原生库瘦身护栏同样作用于本 jar。
    implementation(CubexDeps.sqliteJdbc)

    testImplementation(CubexDeps.junitJupiter)
}

tasks.shadowJar {
    archiveBaseName.set("CubeXLib")
    // Adventure 由 Paper 提供(与 Contract 同一取舍),不 bundle 也不 relocate。
    exclude("net/kyori/**")
    // FoliaLib 是 cubex-scheduler 的实现细节,不向插件泄漏 —— relocate 进本插件命名空间。
    relocate("com.tcoded.folialib", "${CubexRelocations.libsNamespace(project.name)}.folialib")
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
