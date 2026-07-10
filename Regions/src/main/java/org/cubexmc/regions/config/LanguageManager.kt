package org.cubexmc.regions.config

import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.regions.RegionsPlugin
import java.io.File
import java.util.regex.Pattern

class LanguageManager(private val plugin: RegionsPlugin) {
    private var messages = YamlConfiguration()
    private var locale = "zh_CN"

    fun load() {
        locale = plugin.config.getString("language", "zh_CN") ?: "zh_CN"
        val file = File(plugin.dataFolder, "lang/$locale.yml")
        messages = if (file.exists()) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
    }

    fun message(key: String, placeholders: Map<String, String> = emptyMap()): String {
        var value = messages.getString(key) ?: key
        for ((placeholder, replacement) in placeholders) {
            value = value.replace("{$placeholder}", replacement)
        }
        return color(value)
    }

    fun prefixed(key: String, placeholders: Map<String, String> = emptyMap()): String =
        message("prefix") + message(key, placeholders)

    fun send(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        for (line in prefixed(key, placeholders).split(Regex("\\R"))) {
            sender.sendMessage(line)
        }
    }

    fun sendRaw(sender: CommandSender, raw: String) {
        for (line in color(raw).split(Regex("\\R"))) {
            sender.sendMessage(line)
        }
    }

    private fun color(input: String): String {
        var output = input
        val matcher = HEX_PATTERN.matcher(output)
        val builder = StringBuffer()
        while (matcher.find()) {
            val hex = matcher.group(1)
            val replacement = StringBuilder("§x")
            for (char in hex.toCharArray()) {
                replacement.append('§').append(char)
            }
            matcher.appendReplacement(builder, replacement.toString())
        }
        matcher.appendTail(builder)
        output = builder.toString()
        return ChatColor.translateAlternateColorCodes('&', output)
    }

    companion object {
        private val HEX_PATTERN: Pattern = Pattern.compile("&#([A-Fa-f0-9]{6})")
    }
}
