/**
 * `createPlugin` 的纯逻辑：只做**字符串生成**与**登记文本改写**，不碰文件系统。
 *
 * 这样它能被 buildSrc 的单测直接覆盖 —— 脚手架写错的代价是"生成出来的插件构建不了"，
 * 而那正是新人和 agent 最难自己诊断的一类失败，所以这层必须有测试兜着。
 *
 * 它要自动完成的是最容易漏、漏了就炸的几步（见 `PLAN.md` §7.3）：
 * `settings.gradle.kts` 登记、`CubexRelocations.kt` 的 pluginId（漏了 shadowJar 报 `Key X missing`）、
 * 按打包模式生成 `plugin.yml`（外置模式的 `depend` 由构建注入，不写进模板）。
 */
object CubexScaffold {

    private val NAME_PATTERN = Regex("[A-Z][A-Za-z0-9]{1,31}")
    private val PACKAGE_PATTERN = Regex("""[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)*""")

    /** 允许出现在 `--modules` 里的短名，与 `CubexModules` 的名单同源。 */
    val KNOWN_MODULES: List<String> = listOf(
        "core", "config", "i18n", "scheduler", "integrations",
        "database", "command", "gui", "spatial",
    )

    fun requireValidName(name: String): String {
        require(NAME_PATTERN.matches(name)) {
            "插件名必须是大写字母开头的 2-32 位字母数字(例如 MyPlugin),实际: $name"
        }
        return name
    }

    fun defaultPackage(name: String): String = "org.cubexmc." + name.lowercase()

    fun requireValidPackage(packageName: String): String {
        require(PACKAGE_PATTERN.matches(packageName)) {
            "包名必须是小写字母数字与点(例如 org.cubexmc.myplugin),实际: $packageName"
        }
        return packageName
    }

    /** pluginId 用于 relocate 命名空间，必须唯一且全小写。 */
    fun pluginId(name: String): String = name.lowercase()

    /**
     * 归一化 `--modules`：去重、保序、剔空，并保证 core 永远在最前（它是必选模块）。
     */
    fun normalizeModules(requested: Collection<String>): List<String> {
        val cleaned = requested.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val unknown = cleaned.filterNot { it in KNOWN_MODULES }
        require(unknown.isEmpty()) {
            "未知模块: ${unknown.joinToString(", ")};可选: ${KNOWN_MODULES.joinToString(", ")}"
        }
        return (listOf("core") + cleaned).distinct()
    }

    fun buildScript(name: String, mode: CubexPackagingMode, modules: List<String>): String {
        val modeLine = when (mode) {
            CubexPackagingMode.EMBEDDED ->
                "// 内嵌模式(默认):cubex-* 与 Kotlin stdlib shade + relocate 进本 jar,可单独安装。"
            CubexPackagingMode.EXTERNAL ->
                "// 外置模式:不 shade 任何 cubex-*,运行时由 CubeXLib 提供。\n" +
                    "// plugin.yml 的 depend: [CubeXLib] 由构建注入,**不要手写**。\n" +
                    "cubex { packaging.set(CubexPackagingMode.EXTERNAL) }"
            CubexPackagingMode.LIB ->
                throw IllegalArgumentException("LIB 模式是 CubeXLib 专用,脚手架不生成")
        }
        val moduleLines = modules.joinToString("\n") { "    implementation(project(\":modules:cubex-$it\"))" }

        return """
            |plugins { id("cubex-kotlin-plugin") }
            |
            |version = "0.1.0"
            |description = "$name"
            |
            |$modeLine
            |
            |dependencies {
            |    compileOnly(CubexDeps.spigotApi("1.18.2-R0.1-SNAPSHOT"))
            |
            |$moduleLines
            |
            |    testImplementation(CubexDeps.junitJupiter)
            |}
            |
            |tasks.processResources {
            |    filesMatching("plugin.yml") { expand("version" to project.version) }
            |}
            |
            |tasks.shadowJar { archiveBaseName.set("$name") }
            |
        """.trimMargin()
    }

    fun pluginYml(name: String, packageName: String, mode: CubexPackagingMode): String {
        val dependNote = if (mode == CubexPackagingMode.EXTERNAL) {
            "# depend 由构建按打包模式注入,这里**不要**写 CubeXLib。\n"
        } else {
            ""
        }
        return """
            |name: $name
            |version: '${'$'}{version}'
            |main: $packageName.${name}Plugin
            |api-version: '1.18'
            |author: CubeX
            |description: $name
            |$dependNote
        """.trimMargin()
    }

    fun mainClassSource(name: String, packageName: String): String = """
        |package $packageName
        |
        |import org.cubexmc.core.CubexPlugin
        |
        |class ${name}Plugin : CubexPlugin() {
        |
        |    override fun enablePlugin() {
        |        log().info("$name enabled")
        |    }
        |}
        |
    """.trimMargin()

    fun smokeTestSource(name: String, packageName: String): String = """
        |package $packageName
        |
        |import org.junit.jupiter.api.Assertions.assertTrue
        |import org.junit.jupiter.api.Test
        |
        |class ${name}PluginTest {
        |
        |    @Test
        |    fun `main class extends CubexPlugin`() {
        |        assertTrue(org.cubexmc.core.CubexPlugin::class.java.isAssignableFrom(${name}Plugin::class.java))
        |    }
        |}
        |
    """.trimMargin()

    /** 把插件名插进 `settings.gradle.kts` 的插件清单 `listOf(...)`。已存在则原样返回。 */
    fun withSettingsEntry(settings: String, name: String): String {
        val quoted = "\"" + name + "\""
        if (Regex(Regex.escape(quoted) + """\s*[,)]""").containsMatchIn(settings)) return settings
        val marker = "\").forEach {"
        require(settings.contains(marker)) {
            "settings.gradle.kts 里找不到插件清单 listOf(...).forEach { ,无法自动登记"
        }
        return settings.replaceFirst(marker, "\", \"$name\").forEach {")
    }

    /** 把 pluginId 插进 `CubexRelocations.kt` 的 map。已存在则原样返回。 */
    fun withRelocationEntry(relocations: String, name: String, id: String): String {
        if (relocations.contains("\"$name\" to ")) return relocations
        val marker = "    )"
        require(relocations.contains(marker)) { "CubexRelocations.kt 结构不符合预期,无法自动登记" }
        return relocations.replaceFirst(marker, "        \"$name\" to \"$id\",\n    )")
    }
}
