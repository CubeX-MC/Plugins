// CubeX-Plugins 根构建脚本:仅定义聚合任务;子项目配置由 cubex-plugin 约定插件负责
group = "org.cubexmc"

// 嵌套 include(如 :cookbook:hello-external)会顺带创建一个**没有构建脚本**的容器项目(:cookbook),
// 它没有 build/clean/jarGate 任何任务。聚合任务必须先把这类项目滤掉,否则报
// "Task with path ':cookbook:jarGate' not found"。
val realProjects = subprojects.filter { it.buildFile.isFile }

// 部署 jar 的产出方 = 除 modules 外的全部真实子项目(插件 + CubeXLib + cookbook 范例)
val deployableProjects = realProjects.filterNot { it.path.startsWith(":modules") }

// 构建所有插件子项目的部署 jar(modules 无 shadowJar,排除之)
tasks.register("shadowJarAll") {
    group = "build"
    description = "构建所有插件子项目的 shadowJar"
    dependsOn(deployableProjects.map { "${it.path}:shadowJar" })
}

tasks.register("buildAllPlugins") {
    group = "build"
    description = "构建所有子项目"
    dependsOn(realProjects.map { "${it.path}:build" })
}

// 对所有插件跑部署 jar 门禁(Kotlin 迁移每批收尾都应跑一次)
tasks.register("jarGateAll") {
    group = "verification"
    description = "对所有插件子项目执行部署 jar 门禁"
    dependsOn(deployableProjects.map { "${it.path}:jarGate" })
}

// Kotlin 迁移进度总览:不依赖子项目求值,直接看源码目录与 build 文件
tasks.register("kotlinMigrationStatus") {
    group = "help"
    description = "打印各子项目 main/test 源码的 Java/Kotlin 文件数与 Kotlin opt-in 状态"

    val rows = realProjects.sortedBy { it.path }.map { it.path to it.projectDir }

    doLast {
        logger.lifecycle("%-26s %-8s %-18s %s".format("project", "kotlin", "main(java/kt)", "test(java/kt)"))
        rows.forEach { (path, dir) ->
            val optIn = File(dir, "build.gradle.kts").takeIf { it.isFile }
                ?.readText()?.let { buildScript ->
                    buildScript.contains("cubex-kotlin-plugin") || buildScript.contains("cubex-kotlin-library")
                } ?: false
            fun count(sourceSet: String, ext: String): Int {
                val root = File(dir, "src/$sourceSet")
                if (!root.isDirectory) return 0
                return root.walkTopDown().count { it.isFile && it.extension == ext }
            }
            logger.lifecycle(
                "%-26s %-8s %-18s %s".format(
                    path,
                    if (optIn) "yes" else "no",
                    "${count("main", "java")}/${count("main", "kt")}",
                    "${count("test", "java")}/${count("test", "kt")}",
                ),
            )
        }
    }
}

// —— 新插件脚手架(PLAN §7.3)——
// 用法: .\gradlew.bat createPlugin -PpluginName=MyPlugin [-Pmode=embedded|external]
//                                   [-Pmodules=core,config,i18n] [-Ppackage=org.cubexmc.myplugin]
// 属性名用 pluginName 而不是 name:后者会和 Gradle 自己的 project.name 撞。
tasks.register("createPlugin") {
    group = "cubex"
    description = "生成新插件子项目骨架,并自动完成 settings 与 CubexRelocations 登记"

    val pluginNameProperty = providers.gradleProperty("pluginName")
    val modeProperty = providers.gradleProperty("mode")
    val modulesProperty = providers.gradleProperty("modules")
    val packageProperty = providers.gradleProperty("package")
    val repoRoot = rootDir

    doLast {
        val name = CubexScaffold.requireValidName(
            pluginNameProperty.orNull ?: error("必须指定 -PpluginName=<MyPlugin>"),
        )
        val mode = when ((modeProperty.orNull ?: "embedded").lowercase()) {
            "embedded" -> CubexPackagingMode.EMBEDDED
            "external" -> CubexPackagingMode.EXTERNAL
            else -> error("-Pmode 只能是 embedded 或 external(LIB 是 CubeXLib 专用)")
        }
        val modules = CubexScaffold.normalizeModules((modulesProperty.orNull ?: "core").split(","))
        val packageName = CubexScaffold.requireValidPackage(
            packageProperty.orNull ?: CubexScaffold.defaultPackage(name),
        )

        val projectDir = File(repoRoot, name)
        require(!projectDir.exists()) { "$name 目录已存在,先删掉或换个名字" }

        val packagePath = packageName.replace('.', '/')
        File(projectDir, "src/main/kotlin/$packagePath").mkdirs()
        File(projectDir, "src/test/kotlin/$packagePath").mkdirs()
        File(projectDir, "src/main/resources").mkdirs()

        // Kotlin 的 writeText 默认 UTF-8 无 BOM —— 别改成 PowerShell 写文件(会带 BOM,javac 直接炸)。
        File(projectDir, "build.gradle.kts")
            .writeText(CubexScaffold.buildScript(name, mode, modules))
        File(projectDir, "src/main/resources/plugin.yml")
            .writeText(CubexScaffold.pluginYml(name, packageName, mode))
        File(projectDir, "src/main/kotlin/$packagePath/${name}Plugin.kt")
            .writeText(CubexScaffold.mainClassSource(name, packageName))
        File(projectDir, "src/test/kotlin/$packagePath/${name}PluginTest.kt")
            .writeText(CubexScaffold.smokeTestSource(name, packageName))

        val settingsFile = File(repoRoot, "settings.gradle.kts")
        settingsFile.writeText(CubexScaffold.withSettingsEntry(settingsFile.readText(), name))

        val relocationsFile = File(repoRoot, "buildSrc/src/main/kotlin/CubexRelocations.kt")
        relocationsFile.writeText(
            CubexScaffold.withRelocationEntry(relocationsFile.readText(), name, CubexScaffold.pluginId(name)),
        )

        logger.lifecycle("")
        logger.lifecycle("[createPlugin] 已生成 $name($mode 模式, 模块: ${modules.joinToString(", ")})")
        logger.lifecycle("  已登记: settings.gradle.kts / CubexRelocations.kt")
        logger.lifecycle("  下一步: gradlew :$name:build :$name:jarGate")
        if (mode == CubexPackagingMode.EXTERNAL) {
            logger.lifecycle("  外置模式: depend: [CubeXLib] 由构建注入,不要写进 plugin.yml")
        }
        logger.lifecycle(
            "  镜像同步: 内部插件**不要**加进 .github/workflows/mirror.yml 的 repos 数组;" +
                "要对外发布则必须先建好目标 repo,否则该 job 会整体失败",
        )
        logger.lifecycle("")
    }
}

tasks.register("cleanAll") {
    group = "build"
    description = "清理所有子项目"
    dependsOn(realProjects.map { "${it.path}:clean" })
}
