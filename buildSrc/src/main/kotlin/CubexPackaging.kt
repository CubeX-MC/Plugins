import org.gradle.api.provider.Property

// 部署 jar 的打包模式。硬约束见 AGENTS.md,路线见 PLAN.md 第 7.1 节。
// 三种模式的编译期代码完全一致,差别只在打包与 plugin.yml。
enum class CubexPackagingMode {
    // 默认。无状态 cubex 模块与 Kotlin stdlib shade + relocate 进自己的 jar,jar 可单独安装。
    // 所有对外发布的插件必须用这个模式。
    EMBEDDED,

    // opt-in。不打包任何 cubex 模块与 Kotlin stdlib,由 CubeXLib 在运行时提供;
    // 构建会往 plugin.yml 注入 depend: [CubeXLib]。只给自服 / 团队内部插件用。
    EXTERNAL,

    // CubeXLib 自己:不 relocate 地打包全部 cubex 模块与 Kotlin stdlib。
    // 全仓只允许一个项目用这个模式,jarGate 会校验。
    LIB,
}

// 由 cubex-plugin 约定插件注册。插件在自己的 build 脚本里写 cubex { packaging.set(...) }。
abstract class CubexPluginExtension {
    abstract val packaging: Property<CubexPackagingMode>
}

// modules/cubex-* 的归档路径前缀 —— 新增共享模块时只改这里。
//
// jarGate 用它区分"共享模块的字节码基线"与"插件自己的 java release"
// (Clarity 是 release 21,漏登记的模块会被拿 major 65 去校验而误判失败);
// EXTERNAL 模式用它把模块类排除出 jar。
object CubexModules {

    // 提供运行时 cubex 模块的插件名,也是 EXTERNAL 模式注入的 depend 条目。
    const val LIB_PLUGIN_NAME = "CubeXLib"

    private val names = listOf(
        "core", "config", "i18n", "scheduler", "integrations",
        "database", "command", "gui", "spatial",
    )

    // 形如 org/cubexmc/core/ 。与 settings.gradle.kts 里的 modules:cubex-* 一一对应。
    val archivePrefixes: List<String> = names.map { name -> "org/cubexmc/" + name + "/" }

    // 形如 org/cubexmc/core/ 加两个星号,供 shadowJar 的 ant 风格 exclude 使用。
    val archivePatterns: List<String> = archivePrefixes.map { prefix -> prefix + "**" }
}

// plugin.yml 的最小改写工具:EXTERNAL 模式下由构建注入 depend,禁止手写。
object CubexPluginYml {

    // 把 plugin 合并进 depend 内联数组;已存在则原样返回。
    // 只支持本仓库实际在用的两种形态:没有 depend 行,或 depend: [A, B] 内联数组。
    // 遇到块状写法会明确报错,而不是悄悄写出坏 yml。
    fun withDepend(text: String, plugin: String): String {
        val lines = text.lines()
        val index = lines.indexOfFirst { line -> line.startsWith("depend:") }
        if (index < 0) {
            return text.trimEnd('\n') + "\ndepend: [" + plugin + "]\n"
        }

        val value = lines[index].substringAfter("depend:").trim()
        require(value.startsWith("[") && value.endsWith("]")) {
            "plugin.yml 的 depend 必须是内联数组才能由构建注入,实际是: " + lines[index]
        }

        val existing = value.removeSurrounding("[", "]")
            .split(",")
            .map { item -> item.trim() }
            .filter { item -> item.isNotEmpty() }
        if (existing.any { item -> item.equals(plugin, ignoreCase = true) }) return text

        val merged = lines.toMutableList()
        merged[index] = "depend: [" + (existing + plugin).joinToString(", ") + "]"
        return merged.joinToString("\n")
    }
}
