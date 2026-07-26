package org.cubexmc.regions.config

import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.regions.RegionsPlugin
import java.io.File

class LanguageManager(private val plugin: RegionsPlugin) {
    private var messages = YamlConfiguration()
    private var locale = "zh_CN"

    fun load() {
        locale = plugin.config.getString("language", "zh_CN") ?: "zh_CN"
        val file = File(plugin.dataFolder, "lang/$locale.yml")
        messages = if (file.exists()) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
    }

    fun message(key: String, placeholders: Map<String, String> = emptyMap()): String =
        fill(messages.getString(key) ?: key, placeholders)

    /**
     * Reads a key that holds a list of lines, used for item lore. A plain string is accepted too and
     * split on newlines, so a translator can collapse a short lore block into one scalar.
     */
    fun messageList(key: String, placeholders: Map<String, String> = emptyMap()): List<String> {
        if (messages.isList(key)) {
            return messages.getStringList(key).map { fill(it, placeholders) }
        }
        val single = messages.getString(key) ?: return emptyList()
        return single.split(Regex("\\R")).map { fill(it, placeholders) }
    }

    fun has(key: String): Boolean = messages.contains(key)

    private fun fill(value: String, placeholders: Map<String, String>): String {
        if (placeholders.isEmpty()) return value
        var filled = value
        for ((placeholder, replacement) in placeholders) {
            filled = filled.replace("{$placeholder}", replacement)
        }
        return filled
    }

    fun prefixed(key: String, placeholders: Map<String, String> = emptyMap()): String =
        message("prefix") + message(key, placeholders)

    fun send(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        for (line in prefixed(key, placeholders).split(Regex("\\R"))) {
            sender.sendMessage(PaperText.parse(line))
        }
    }

    /** Sends a translated message without the plugin prefix, for multi-line command output. */
    fun sendPlain(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        sendRaw(sender, message(key, placeholders))
    }

    fun sendRaw(sender: CommandSender, raw: String) {
        for (line in raw.split(Regex("\\R"))) {
            sender.sendMessage(PaperText.parse(line))
        }
    }
}
