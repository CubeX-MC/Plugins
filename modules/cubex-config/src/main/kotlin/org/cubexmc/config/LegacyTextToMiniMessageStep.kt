package org.cubexmc.config

import org.bukkit.configuration.ConfigurationSection
import java.util.Locale
import java.util.regex.Pattern

class LegacyTextToMiniMessageStep @JvmOverloads constructor(
    private val fromVersionValue: Int,
    private val toVersionValue: Int,
    private val angleBrackets: AngleBrackets = AngleBrackets.ESCAPE,
) : MigrationStep {
    enum class AngleBrackets { ESCAPE, PRESERVE }

    override fun fromVersion(): Int = fromVersionValue
    override fun toVersion(): Int = toVersionValue
    override fun description(): String = "Convert legacy &/hex/placeholder text to MiniMessage."

    override fun migrate(context: MigrationContext) {
        convertSection(context.yaml(), "")
    }

    fun convert(input: String?): String {
        if (input.isNullOrEmpty()) return input.orEmpty()
        val output = StringBuilder(input.length)
        var index = 0
        while (index < input.length) {
            val current = input[index]
            if (current == '&') {
                val consumed = appendLegacyTag(input, index, output)
                if (consumed > index) {
                    index = consumed + 1
                    continue
                }
            }
            if (current == '{') {
                val end = input.indexOf('}', index + 1)
                if (end > index && input.substring(index + 1, end) == "prefix") {
                    output.append("<prefix>")
                    index = end + 1
                    continue
                }
            }
            if (current == '%') {
                val end = input.indexOf('%', index + 1)
                if (end > index) {
                    val name = input.substring(index + 1, end)
                    if (PLACEHOLDER_NAME.matcher(name).matches()) {
                        output.append('<').append(name.lowercase(Locale.ROOT)).append('>')
                        index = end + 1
                        continue
                    }
                }
            }
            appendLiteral(output, current)
            index++
        }
        return output.toString()
    }

    private fun convertSection(section: ConfigurationSection, basePath: String) {
        for (key in section.getKeys(false)) {
            if (section.isConfigurationSection(key)) {
                section.getConfigurationSection(key)?.let { convertSection(it, if (basePath.isEmpty()) key else "$basePath.$key") }
            } else if (section.isString(key)) {
                section[key] = convert(section.getString(key, ""))
            } else if (section.isList(key)) {
                val values = section.getList(key)
                if (values != null && values.all { it is String }) {
                    section[key] = values.map { convert(it as String) }
                }
            }
        }
    }

    private fun appendLegacyTag(input: String, ampersandIndex: Int, output: StringBuilder): Int {
        if (ampersandIndex + 1 >= input.length) return ampersandIndex
        if (input[ampersandIndex + 1] == '#' &&
            ampersandIndex + 7 < input.length &&
            isHex(input, ampersandIndex + 2, ampersandIndex + 8)
        ) {
            output.append("<#").append(input, ampersandIndex + 2, ampersandIndex + 8).append('>')
            return ampersandIndex + 7
        }
        val tag = LEGACY_TAGS[input[ampersandIndex + 1].lowercaseChar()] ?: return ampersandIndex
        output.append('<').append(tag).append('>')
        return ampersandIndex + 1
    }

    private fun isHex(input: String, startInclusive: Int, endExclusive: Int): Boolean =
        (startInclusive until endExclusive).all { input[it].isDigit() || input[it].lowercaseChar() in 'a'..'f' }

    private fun appendLiteral(output: StringBuilder, current: Char) {
        if (current == '<' && angleBrackets == AngleBrackets.ESCAPE) output.append('\\')
        output.append(current)
    }

    private companion object {
        val PLACEHOLDER_NAME: Pattern = Pattern.compile("[a-zA-Z0-9_:-]+")
        val LEGACY_TAGS: Map<Char, String> = mapOf(
            '0' to "black", '1' to "dark_blue", '2' to "dark_green", '3' to "dark_aqua",
            '4' to "dark_red", '5' to "dark_purple", '6' to "gold", '7' to "gray",
            '8' to "dark_gray", '9' to "blue", 'a' to "green", 'b' to "aqua", 'c' to "red",
            'd' to "light_purple", 'e' to "yellow", 'f' to "white", 'k' to "obfuscated",
            'l' to "bold", 'm' to "strikethrough", 'n' to "underlined", 'o' to "italic", 'r' to "reset",
        )
    }
}
