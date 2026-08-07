package org.cubexmc.contract.config

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.contract.ContractPlugin
import org.cubexmc.contract.model.ContractStatus
import org.cubexmc.contract.model.ContractType
import org.cubexmc.contract.model.ParticipantRole
import org.cubexmc.contract.model.PayoutCondition
import org.cubexmc.contract.util.Text
import org.cubexmc.i18n.ColorMode
import org.cubexmc.i18n.I18nOptions
import org.cubexmc.i18n.I18nService
import org.cubexmc.i18n.I18nServices
import org.cubexmc.i18n.MissingKeyMode
import org.cubexmc.i18n.PlaceholderStyle
import java.io.File
import java.util.Locale

class LanguageManager(private val plugin: ContractPlugin) {
    private val i18n: I18nService = I18nServices.create(
        plugin,
        I18nOptions.create()
            .languageDirectory("lang")
            .currentLocale { sanitizeLanguageName(plugin.config.getString("language", "zh_CN")) }
            .defaultLocale("zh_CN")
            .fallbackLocales(listOf("zh_CN"))
            .bundledLocales(listOf("zh_CN", "en_US"))
            .prefixKey("prefix")
            .prefixToken("<prefix>")
            .keyPrefix("messages.")
            .missingKeyMode(MissingKeyMode.RETURN_KEY)
            .placeholderStyles(listOf(PlaceholderStyle.MINIMESSAGE_TAG))
            .colorMode(ColorMode.MINIMESSAGE),
    )
    private lateinit var language: FileConfiguration

    fun load() {
        val configured = plugin.config.getString("language", "zh_CN")
        val languageName = sanitizeLanguageName(configured)
        var file = File(plugin.dataFolder, "lang/$languageName.yml")
        if (!file.exists()) {
            file = File(plugin.dataFolder, "lang/zh_CN.yml")
        }
        language = YamlConfiguration.loadConfiguration(file)
        i18n.reload()
    }

    fun message(path: String, placeholders: Map<String, String>?): String =
        i18n.message(path, placeholders ?: emptyMap<String, String>())

    fun message(path: String): String = message(path, emptyMap<String, String>())

    fun status(status: ContractStatus): String {
        val key = "status." + status.name.lowercase(Locale.ROOT)
        val raw = language.getString(key, status.name)
        return Text.color(raw)
    }

    fun type(type: ContractType): String {
        val key = "types." + type.name.lowercase(Locale.ROOT)
        val raw = language.getString(key, type.name)
        return Text.color(raw)
    }

    fun role(role: ParticipantRole): String {
        val key = "roles." + role.name.lowercase(Locale.ROOT)
        val raw = language.getString(key, role.name)
        return Text.color(raw)
    }

    fun condition(condition: PayoutCondition): String {
        val key = "conditions." + condition.name.lowercase(Locale.ROOT)
        val raw = language.getString(key, condition.name)
        return Text.color(raw)
    }

    fun term(key: String, fallback: String): String {
        val raw = language.getString("terms.$key", fallback)
        return Text.color(raw)
    }

    private fun sanitizeLanguageName(configured: String?): String {
        if (configured.isNullOrBlank()) {
            return "zh_CN"
        }
        val sanitized = configured.replace("[^A-Za-z0-9_-]".toRegex(), "")
        return if (sanitized.isBlank()) "zh_CN" else sanitized
    }
}
