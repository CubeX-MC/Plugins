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

tasks.register("cleanAll") {
    group = "build"
    description = "清理所有子项目"
    dependsOn(subprojects.map { "${it.path}:clean" })
}
