package org.cubexmc.statecharge.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeSet
import java.util.regex.Pattern
import java.util.stream.Stream

/**
 * 守卫插件的国际化契约(照抄 Contract 的 LanguageParityTest):
 * - 每份 bundle 语言文件定义完全相同的 key 集,切换语言不会静默降级成裸 key;
 * - 源码里 `ui("...")` / `message("...")` 引用的 key 都真实存在;
 * - 各语言保持相同的 `<placeholder>` token;
 * - Kotlin 里没有 legacy 颜色字面量(所有可见颜色都在语言文件)。
 */
class LanguageParityTest {

    @Test
    fun bundledLocalesDefineTheSameKeys() {
        val base = load(LOCALES[0])
        val baseKeys = base.getKeys(true)

        for (locale in LOCALES.subList(1, LOCALES.size)) {
            val otherKeys = load(locale).getKeys(true)
            val missing = TreeSet(baseKeys).apply { removeAll(otherKeys) }
            val extra = TreeSet(otherKeys).apply { removeAll(baseKeys) }

            assertTrue(missing.isEmpty(), "$locale.yml is missing keys: $missing")
            assertTrue(extra.isEmpty(), "$locale.yml has keys no other locale defines: $extra")
        }
    }

    @Test
    fun translationsKeepTheSamePlaceholders() {
        val base = load(LOCALES[0])

        for (locale in LOCALES.subList(1, LOCALES.size)) {
            val other = load(locale)
            for (key in base.getKeys(true)) {
                val baseValue = base.getString(key)
                val otherValue = other.getString(key)
                if (baseValue == null || otherValue == null || isCommandSyntax(key)) {
                    continue
                }
                assertEquals(
                    placeholders(baseValue),
                    placeholders(otherValue),
                    "placeholder mismatch for $key in $locale.yml",
                )
            }
        }
    }

    @Test
    fun everyUiKeyReferencedInSourcesIsDefined() {
        val base = load(LOCALES[0])
        val defined = base.getKeys(true)
            .filter { it.startsWith("ui.") }
            .map { it.substring("ui.".length) }
            .toMutableSet()

        val missing = TreeSet<String>()
        val uiPattern = Pattern.compile("\\bui\\(\\s*\"([a-z][a-z0-9-]*)\"")
        for (source in kotlinSources()) {
            val matcher = uiPattern.matcher(source.first)
            while (matcher.find()) {
                if (!defined.contains(matcher.group(1))) {
                    missing.add(matcher.group(1) + "  (" + source.second.fileName + ")")
                }
            }
        }

        assertTrue(missing.isEmpty(), "ui keys used in code but not defined in lang files: $missing")
    }

    @Test
    fun everyMessageKeyReferencedInSourcesIsDefined() {
        val base = load(LOCALES[0])
        val defined = base.getKeys(true)
            .filter { it.startsWith("messages.") }
            .map { it.substring("messages.".length) }
            .toMutableSet()

        val missing = TreeSet<String>()
        val messagePattern = Pattern.compile("\\bmessage\\(\\s*\"([a-z][a-z0-9-]*)\"")
        for (source in kotlinSources()) {
            val matcher = messagePattern.matcher(source.first)
            while (matcher.find()) {
                if (!defined.contains(matcher.group(1))) {
                    missing.add(matcher.group(1) + "  (" + source.second.fileName + ")")
                }
            }
        }

        assertTrue(missing.isEmpty(), "message keys used in code but not defined in lang files: $missing")
    }

    @Test
    fun noKotlinSourceEmitsLegacyColourCodes() {
        // 所有玩家可见字符串都经语言文件渲染;Kotlin 里遗留的 "&#RRGGBB" 会原样打给玩家。
        val legacyColour = Pattern.compile("\"[^\"]*&#[0-9A-Fa-f]{6}")
        val offenders = mutableListOf<String>()
        for (source in kotlinSources()) {
            val lines = source.first.lines()
            for ((index, line) in lines.withIndex()) {
                if (line.trimStart().startsWith("*") || line.trimStart().startsWith("//")) {
                    continue
                }
                if (legacyColour.matcher(line).find()) {
                    offenders.add(source.second.fileName.toString() + ":" + (index + 1))
                }
            }
        }

        assertTrue(offenders.isEmpty(), "legacy &#RRGGBB colour literals must live in the language files, not in Kotlin: $offenders")
    }

    /**
     * usage 行与帮助块拼写命令语法(如 `<state> [count]`),尖括号是给玩家看的参数名而非
     * 替换占位符,各语言用自己的语言命名它们。
     */
    private fun isCommandSyntax(key: String): Boolean =
        key.startsWith("ui.usage-") || key == "messages.help"

    private fun placeholders(value: String): Set<String> {
        val found = TreeSet<String>()
        val matcher = PLACEHOLDER.matcher(value)
        while (matcher.find()) {
            found.add(matcher.group(1))
        }
        // <prefix> 由 i18n 层替换,不来自调用方。
        found.remove("prefix")
        return found
    }

    private fun kotlinSources(): List<Pair<String, Path>> {
        val sources = Path.of("src", "main", "kotlin")
        val files = mutableListOf<Pair<String, Path>>()
        Files.walk(sources).use { walk: Stream<Path> ->
            for (path in walk.filter { it.toString().endsWith(".kt") }.toList()) {
                files.add(Files.readString(path, StandardCharsets.UTF_8) to path)
            }
        }
        return files
    }

    private fun load(locale: String): YamlConfiguration {
        val file = File("src/main/resources/lang/$locale.yml")
        assertTrue(file.isFile, "missing bundled locale: $file")
        return YamlConfiguration.loadConfiguration(file)
    }

    private companion object {
        val LOCALES = listOf("zh_CN", "en_US")
        val PLACEHOLDER = Pattern.compile("<([a-z][a-z0-9_-]*)>")
    }
}
