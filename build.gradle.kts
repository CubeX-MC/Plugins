// CubeX-Plugins 根构建脚本:仅定义聚合任务;子项目配置由 cubex-plugin 约定插件负责
group = "org.cubexmc"

// 构建所有插件子项目的部署 jar(modules 无 shadowJar,排除之)
tasks.register("shadowJarAll") {
    group = "build"
    description = "构建所有插件子项目的 shadowJar"
    dependsOn(subprojects.filterNot { it.path.startsWith(":modules") }.map { "${it.path}:shadowJar" })
}

tasks.register("buildAllPlugins") {
    group = "build"
    description = "构建所有子项目"
    dependsOn(subprojects.map { "${it.path}:build" })
}

// 对所有插件跑部署 jar 门禁(Kotlin 迁移每批收尾都应跑一次)
tasks.register("jarGateAll") {
    group = "verification"
    description = "对所有插件子项目执行部署 jar 门禁"
    dependsOn(subprojects.filterNot { it.path.startsWith(":modules") }.map { "${it.path}:jarGate" })
}

// Kotlin 迁移进度总览:不依赖子项目求值,直接看源码目录与 build 文件
tasks.register("kotlinMigrationStatus") {
    group = "help"
    description = "打印各子项目 main/test 源码的 Java/Kotlin 文件数与 Kotlin opt-in 状态"

    val rows = subprojects.sortedBy { it.path }.map { it.path to it.projectDir }

    doLast {
        logger.lifecycle("%-26s %-8s %-18s %s".format("project", "kotlin", "main(java/kt)", "test(java/kt)"))
        rows.forEach { (path, dir) ->
            val optIn = File(dir, "build.gradle.kts").takeIf { it.isFile }
                ?.readText()?.contains("cubex-kotlin-plugin") ?: false
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

tasks.register("cleanAll") {
    group = "build"
    description = "清理所有子项目"
    dependsOn(subprojects.map { "${it.path}:clean" })
}
