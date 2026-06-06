package org.cubexmc.ecobalancer.utils

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.ComponentBuilder
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.FileConfiguration
import java.util.Locale
import java.util.logging.Logger
import java.util.regex.Pattern

object MessageUtils {
    private val placeholderName: Pattern = Pattern.compile("[a-z0-9_:-]+")
    private val legacyColorMarker: Pattern = Pattern.compile("(?i)(?:&(?:#[0-9a-f]{6}|[0-9a-fk-or])|§[0-9a-fk-or])")
    private val miniMessage: MiniMessage = MiniMessage.miniMessage()
    private val legacySerializer: LegacyComponentSerializer = LegacyComponentSerializer.builder()
        .character(LegacyComponentSerializer.SECTION_CHAR)
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build()
    private val legacyTags: Map<Char, String> = mapOf(
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

    @JvmStatic
    fun formatMessage(
        config: FileConfiguration,
        path: String,
        placeholders: Map<String, String>?,
        prefix: String,
    ): String {
        val map: MutableMap<String, String> = HashMap()
        if (placeholders != null) {
            map.putAll(placeholders)
        }
        val message = config.getString(path, "Message not found!")
        return renderMiniMessage(message, map, prefix)
    }

    @JvmStatic
    fun formatComponent(
        config: FileConfiguration,
        path: String,
        placeholders: Map<String, String>?,
        clickablePlaceholders: Array<String>?,
        clickableComponents: Array<TextComponent>?,
        prefix: String,
    ): TextComponent {
        val map: MutableMap<String, String> = HashMap()
        if (placeholders != null) {
            map.putAll(placeholders)
        }

        val message = config.getString(path, "Message not found!") ?: "Message not found!"

        var finalMessage = TextComponent()
        if (clickablePlaceholders != null && clickableComponents != null) {
            var cursor = 0
            while (cursor < message.length) {
                val match = nextClickable(message, cursor, clickablePlaceholders)
                if (match == null) {
                    finalMessage.addExtra(TextComponent(renderMiniMessage(message.substring(cursor), map, prefix)))
                    break
                }
                if (match.start > cursor) {
                    finalMessage.addExtra(TextComponent(renderMiniMessage(message.substring(cursor, match.start), map, prefix)))
                }
                finalMessage.addExtra(clickableComponents[match.index])
                cursor = match.end
            }
        } else {
            finalMessage = TextComponent(renderMiniMessage(message, map, prefix))
        }

        return finalMessage
    }

    @JvmStatic
    fun renderMiniMessage(template: String?, placeholders: Map<String, String>?, legacyPrefix: String): String {
        var message = template ?: ""
        val builder = TagResolver.builder()
        builder.resolver(Placeholder.component("prefix", legacyComponent(legacyPrefix)))
        if (placeholders != null) {
            for ((name, rawValue) in placeholders) {
                val normalized = name.lowercase(Locale.ROOT)
                if (!placeholderName.matcher(normalized).matches()) {
                    continue
                }
                val value = rawValue
                if (containsLegacyStyle(value)) {
                    val miniMessageValue = legacyTextToMiniMessage(value)
                    message = message.replace("<$normalized>", miniMessageValue)
                    message = message.replace("%$name%", miniMessageValue)
                } else {
                    builder.resolver(Placeholder.unparsed(normalized, value))
                }
            }
        }
        val component = miniMessage.deserialize(message, builder.build())
        return legacySerializer.serialize(component)
    }

    private fun legacyComponent(value: String?): Component =
        legacySerializer.deserialize(ChatColor.translateAlternateColorCodes('&', value ?: ""))

    private fun containsLegacyStyle(value: String?): Boolean =
        value != null && legacyColorMarker.matcher(value).find()

    private fun legacyTextToMiniMessage(value: String?): String {
        val input = (value ?: "").replace('§', '&')
        val output = StringBuilder(input.length)
        var index = 0
        while (index < input.length) {
            val current = input[index]
            if (current == '&' && index + 1 < input.length) {
                if (input[index + 1] == '#' && index + 7 < input.length && isHex(input, index + 2, index + 8)) {
                    output.append("<#").append(input, index + 2, index + 8).append('>')
                    index += 8
                    continue
                }
                val tag = legacyTags[input[index + 1].lowercaseChar()]
                if (tag != null) {
                    output.append('<').append(tag).append('>')
                    index += 2
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

    private fun nextClickable(message: String, start: Int, clickablePlaceholders: Array<String>): ClickableMatch? {
        var best: ClickableMatch? = null
        for (i in clickablePlaceholders.indices) {
            val name = clickablePlaceholders[i]
            if (name.isBlank()) {
                continue
            }
            val tagToken = "<${name.lowercase(Locale.ROOT)}>"
            val tagIndex = message.indexOf(tagToken, start)
            if (tagIndex >= 0 && (best == null || tagIndex < best.start)) {
                best = ClickableMatch(tagIndex, tagIndex + tagToken.length, i)
            }
            val legacyToken = "%$name%"
            val legacyIndex = message.indexOf(legacyToken, start)
            if (legacyIndex >= 0 && (best == null || legacyIndex < best.start)) {
                best = ClickableMatch(legacyIndex, legacyIndex + legacyToken.length, i)
            }
        }
        return best
    }

    private class ClickableMatch(
        val start: Int,
        val end: Int,
        val index: Int,
    )

    @JvmStatic
    fun sendMessage(sender: CommandSender?, message: String, logger: Logger?, isLog: Boolean) {
        if (sender != null) {
            for (str in message.split("\n")) {
                sender.sendMessage(str)
            }
        }
        if (isLog && logger != null) {
            for (str in message.split("\n")) {
                logger.info(str)
            }
        }
    }

    @JvmStatic
    fun createClickableComponent(
        text: String,
        clickAction: ClickEvent.Action,
        clickValue: String,
        hoverText: String?,
    ): TextComponent {
        val component = TextComponent(text)
        component.clickEvent = ClickEvent(clickAction, clickValue)
        if (!hoverText.isNullOrEmpty()) {
            component.hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, ComponentBuilder(hoverText).create())
        }
        return component
    }
}
