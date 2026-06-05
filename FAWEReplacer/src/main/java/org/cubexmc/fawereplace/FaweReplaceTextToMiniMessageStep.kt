package org.cubexmc.fawereplace

import java.util.Locale
import java.util.regex.Pattern
import org.bukkit.configuration.ConfigurationSection
import org.cubexmc.config.MigrationContext
import org.cubexmc.config.MigrationStep

class FaweReplaceTextToMiniMessageStep(
    private val fromVersion: Int,
    private val toVersion: Int,
) : MigrationStep {
    override fun fromVersion(): Int = fromVersion

    override fun toVersion(): Int = toVersion

    override fun description(): String =
        "Convert FAWEReplace legacy section colors and brace placeholders to MiniMessage."

    override fun migrate(context: MigrationContext) {
        convertSection(context.yaml(), "")
    }

    fun convert(input: String?): String {
        if (input.isNullOrEmpty()) {
            return input ?: ""
        }
        val output = StringBuilder(input.length)
        var index = 0
        while (index < input.length) {
            val current = input[index]
            if (current == '&' || current == '§') {
                val consumed = appendLegacyTag(input, index, output)
                if (consumed > index) {
                    index = consumed + 1
                    continue
                }
            }
            if (current == '{') {
                val end = input.indexOf('}', index + 1)
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
            val path = if (basePath.isEmpty()) key else "$basePath.$key"
            if (section.isConfigurationSection(key)) {
                section.getConfigurationSection(key)?.let { convertSection(it, path) }
            } else if (section.isString(key)) {
                section.set(key, convert(section.getString(key, "")))
            } else if (section.isList(key)) {
                val values = section.getList(key)
                if (values != null && values.all { it is String }) {
                    section.set(key, values.map { convert(it as String) })
                }
            }
        }
    }

    private fun appendLegacyTag(input: String, markerIndex: Int, output: StringBuilder): Int {
        if (markerIndex + 1 >= input.length) {
            return markerIndex
        }
        if (input[markerIndex + 1] == '#'
            && markerIndex + 7 < input.length
            && isHex(input, markerIndex + 2, markerIndex + 8)
        ) {
            output.append("<reset><#").append(input, markerIndex + 2, markerIndex + 8).append('>')
            return markerIndex + 7
        }
        val code = input[markerIndex + 1].lowercaseChar()
        val tag = LEGACY_TAGS[code] ?: return markerIndex
        if (isLegacyColor(code) || code == 'r') {
            output.append("<reset>")
            if (code != 'r') {
                output.append('<').append(tag).append('>')
            }
        } else {
            output.append('<').append(tag).append('>')
        }
        return markerIndex + 1
    }

    private fun isLegacyColor(code: Char): Boolean =
        code in '0'..'9' || code in 'a'..'f'

    private fun isHex(input: String, startInclusive: Int, endExclusive: Int): Boolean {
        for (index in startInclusive until endExclusive) {
            val char = input[index]
            val hex = char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
            if (!hex) {
                return false
            }
        }
        return true
    }

    private fun appendLiteral(output: StringBuilder, current: Char) {
        if (current == '<') {
            output.append('\\')
        }
        output.append(current)
    }

    private companion object {
        val PLACEHOLDER_NAME: Pattern = Pattern.compile("[a-zA-Z0-9_:-]+")
        val LEGACY_TAGS: Map<Char, String> = mapOf(
            '0' to "black",
            '1' to "dark_blue",
            '2' to "dark_green",
            '3' to "dark_aqua",
            '4' to "dark_red",
            '5' to "dark_purple",
            '6' to "gold",
            '7' to "gray",
            '8' to "dark_gray",
            '9' to "blue",
            'a' to "green",
            'b' to "aqua",
            'c' to "red",
            'd' to "light_purple",
            'e' to "yellow",
            'f' to "white",
            'k' to "obfuscated",
            'l' to "bold",
            'm' to "strikethrough",
            'n' to "underlined",
            'o' to "italic",
            'r' to "reset",
        )
    }
}
