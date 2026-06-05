plugins { id("cubex-plugin") }

version = "1.0.0"
description = "Clarity — scan and purge orphaned player state and item metadata left by removed plugins"

dependencies {
    // 需要现代属性 API(NamespacedKey + Registry.ATTRIBUTE + AttributeModifier#getKey),仅 1.20.5/1.21 有,
    // 故对 Paper 1.21 编译(其余插件走 1.16.5 兼容线,本插件是例外)。
    compileOnly(CubexDeps.paperApi("1.21.11-R0.1-SNAPSHOT"))
    testImplementation(CubexDeps.junitJupiter)
}

// 覆盖体系默认 release=17 → 21:本插件用 1.21 API,生产服运行在 Java 21。
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }

// 让 plugin.yml 里的 ${version} 生效(借鉴 StatCleaner,避免硬编码版本)。
tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.runServer { minecraftVersion("1.21.4") }

tasks.shadowJar { archiveBaseName.set("Clarity") }
