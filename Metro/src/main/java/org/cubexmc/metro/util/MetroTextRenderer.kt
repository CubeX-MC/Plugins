package org.cubexmc.metro.util

import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

object MetroTextRenderer {
    private val PLACEHOLDER_NAME: Pattern = Pattern.compile("[a-z0-9_:-]+")
    private val UNRESOLVED_TAG: Pattern = Pattern.compile("(?<!\\\\)<([a-z0-9_:-]+)>")
    private val LEGACY_MARKER: Pattern = Pattern.compile("(?i)(?:&(?:#[0-9a-f]{6}|[0-9a-fk-or])|§[0-9a-fk-or])")
    private val MINIMESSAGE_TAGS: Set<String> = setOf(
        "black",
        "dark_blue",
        "dark_green",
        "dark_aqua",
        "dark_red",
        "dark_purple",
        "gold",
        "gray",
        "dark_gray",
        "blue",
        "green",
        "aqua",
        "red",
        "light_purple",
        "yellow",
        "white",
        "obfuscated",
        "bold",
        "strikethrough",
        "underlined",
        "italic",
        "reset",
    )
    private val TRUSTED_FORMATTED_PLACEHOLDERS: Set<String> = setOf(
        "line_color_code",
        "status",
        "state",
        "routes",
        "title",
        "subtitle",
    )

    private val MINI_MESSAGE: MiniMessage = MiniMessage.miniMessage()
    private val LEGACY_SERIALIZER: LegacyComponentSerializer = LegacyComponentSerializer.builder()
        .character(LegacyComponentSerializer.SECTION_CHAR)
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build()

    @JvmStatic
    fun render(template: String?): String = render(template, emptyMap<String, Any?>())

    @JvmStatic
    fun render(template: String?, placeholders: Map<String, *>?): String {
        val normalized = normalizeLegacyMarkers(template ?: "")
        val resolver = resolver(placeholders)
        val component = MINI_MESSAGE.deserialize(normalized, resolver)
        return LEGACY_SERIALIZER.serialize(component)
    }

    @JvmStatic
    fun renderPreservingPlaceholders(template: String?): String {
        var normalized = normalizeLegacyMarkers(template ?: "")
        normalized = preserveUnresolvedPlaceholders(normalized)
        val component = MINI_MESSAGE.deserialize(normalized)
        return LEGACY_SERIALIZER.serialize(component)
    }

    @JvmStatic
    fun convertLegacyTemplate(input: String?): String {
        if (input == null || input.isEmpty()) {
            return input ?: ""
        }
        val output = StringBuilder(input.length)
        var index = 0
        while (index < input.length) {
            val current = input[index]
            if (current == '&' || current == '§') {
                val consumed = appendLegacyTag(input, index, current, output)
                if (consumed > index) {
                    index = consumed + 1
                    continue
                }
            }
            if (current == '{') {
                val end = input.indexOf('}', index + 1)
                if (end > index) {
                    val name = input.substring(index + 1, end).lowercase(Locale.ROOT)
                    if (PLACEHOLDER_NAME.matcher(name).matches()) {
                        output.append('<').append(name).append('>')
                        index = end + 1
                        continue
                    }
                }
            }
            if (current == '%') {
                val consumed = appendPercentPlaceholder(input, index, output)
                if (consumed > index) {
                    index = consumed + 1
                    continue
                }
            }
            if (current == '<') {
                output.append('\\')
            }
            output.append(current)
            index++
        }
        return output.toString()
    }

    @JvmStatic
    fun hasLegacyMarker(value: String?): Boolean = value != null && LEGACY_MARKER.matcher(value).find()

    private fun resolver(placeholders: Map<String, *>?): TagResolver {
        val builder = TagResolver.builder()
        if (placeholders == null || placeholders.isEmpty()) {
            return builder.build()
        }
        for ((rawName, rawValue) in placeholders) {
            val name = normalizePlaceholderName(rawName) ?: continue
            val value = rawValue?.toString() ?: ""
            if (TRUSTED_FORMATTED_PLACEHOLDERS.contains(name)) {
                builder.resolver(Placeholder.parsed(name, normalizeLegacyMarkers(value)))
            } else {
                builder.resolver(Placeholder.unparsed(name, value))
            }
        }
        return builder.build()
    }

    private fun normalizePlaceholderName(name: String?): String? {
        if (name == null) {
            return null
        }
        val normalized = name.lowercase(Locale.ROOT)
        return if (PLACEHOLDER_NAME.matcher(normalized).matches()) normalized else null
    }

    private fun preserveUnresolvedPlaceholders(template: String): String {
        val matcher = UNRESOLVED_TAG.matcher(template)
        val buffer = StringBuffer()
        while (matcher.find()) {
            val name = matcher.group(1)
            if (isMiniMessageTag(name)) {
                continue
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("{$name}"))
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    private fun isMiniMessageTag(name: String?): Boolean =
        name != null && (MINIMESSAGE_TAGS.contains(name) || name.startsWith("#"))

    private fun normalizeLegacyMarkers(value: String?): String {
        if (value == null || value.isEmpty()) {
            return value ?: ""
        }
        val output = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val current = value[index]
            if (current == '&' || current == '§') {
                val consumed = appendLegacyTag(value, index, current, output)
                if (consumed > index) {
                    index = consumed + 1
                    continue
                }
            }
            output.append(current)
            index++
        }
        return output.toString()
    }

    private fun appendPercentPlaceholder(input: String, percentIndex: Int, output: StringBuilder): Int {
        val next = percentIndex + 1
        if (next >= input.length) {
            return percentIndex
        }
        if (Character.isDigit(input[next])) {
            var end = next
            while (end < input.length && Character.isDigit(input[end])) {
                end++
            }
            output.append("<arg").append(input, next, end).append('>')
            return end - 1
        }
        val closing = input.indexOf('%', next)
        if (closing > percentIndex) {
            val name = input.substring(next, closing).lowercase(Locale.ROOT)
            if (PLACEHOLDER_NAME.matcher(name).matches()) {
                output.append('<').append(name).append('>')
                return closing
            }
        }
        return percentIndex
    }

    private fun appendLegacyTag(input: String, markerIndex: Int, marker: Char, output: StringBuilder): Int {
        if (markerIndex + 1 >= input.length) {
            return markerIndex
        }
        if (
            marker == '&' &&
            input[markerIndex + 1] == '#' &&
            markerIndex + 7 < input.length &&
            isHex(input, markerIndex + 2, markerIndex + 8)
        ) {
            output.append("<#").append(input, markerIndex + 2, markerIndex + 8).append('>')
            return markerIndex + 7
        }
        val code = Character.toLowerCase(input[markerIndex + 1])
        val tag = when (code) {
            '0' -> "black"
            '1' -> "dark_blue"
            '2' -> "dark_green"
            '3' -> "dark_aqua"
            '4' -> "dark_red"
            '5' -> "dark_purple"
            '6' -> "gold"
            '7' -> "gray"
            '8' -> "dark_gray"
            '9' -> "blue"
            'a' -> "green"
            'b' -> "aqua"
            'c' -> "red"
            'd' -> "light_purple"
            'e' -> "yellow"
            'f' -> "white"
            'k' -> "obfuscated"
            'l' -> "bold"
            'm' -> "strikethrough"
            'n' -> "underlined"
            'o' -> "italic"
            'r' -> "reset"
            else -> null
        } ?: return markerIndex
        output.append('<').append(tag).append('>')
        return markerIndex + 1
    }

    private fun isHex(input: String, startInclusive: Int, endExclusive: Int): Boolean {
        for (index in startInclusive until endExclusive) {
            val c = input[index]
            val hex = (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')
            if (!hex) {
                return false
            }
        }
        return true
    }
}
