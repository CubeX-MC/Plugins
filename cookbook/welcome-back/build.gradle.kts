plugins { id("cubex-kotlin-plugin") }

version = "1.0.0"
description = "Cookbook 02 — onEvent 自动绑定"

// 没有 cubex { packaging.set(...) }:默认就是内嵌模式。
// 对外发布的插件必须走这一条,cookbook 里留一篇内嵌的,免得只有外置模式被验证到。

dependencies {
    compileOnly(CubexDeps.spigotApi("1.18.2-R0.1-SNAPSHOT"))
    implementation(project(":modules:cubex-core"))
    testImplementation(CubexDeps.junitJupiter)
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.shadowJar { archiveBaseName.set("CookbookWelcomeBack") }
