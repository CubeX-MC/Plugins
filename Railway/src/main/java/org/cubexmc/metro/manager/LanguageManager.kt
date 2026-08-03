package org.cubexmc.metro.manager

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.i18n.ColorMode
import org.cubexmc.i18n.I18nOptions
import org.cubexmc.i18n.I18nService
import org.cubexmc.i18n.I18nServices
import org.cubexmc.i18n.MissingKeyMode
import org.cubexmc.i18n.PlaceholderStyle
import org.cubexmc.metro.Metro
import org.cubexmc.metro.update.LanguageUpdater
import org.cubexmc.metro.update.MetroMigrations
import org.cubexmc.metro.util.MetroTextRenderer
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.logging.Level

/** 管理多语言消息。 */
class LanguageManager(
    private val plugin: Metro,
) {
    private val languageFiles: MutableMap<String, YamlConfiguration> = HashMap()
    private var defaultLanguage = "zh_CN"
    private var currentLanguage = "zh_CN"
    private val i18n: I18nService = I18nServices.create(
        plugin,
        I18nOptions.create()
            .languageDirectory("lang")
            .currentLocale { currentLanguage }
            .defaultLocale(defaultLanguage)
            .fallbackLocales(listOf("en_US", "zh_CN"))
            .bundledLocales(MetroMigrations.BUNDLED_LANGUAGES)
            .prefixToken("<prefix>")
            .missingKeyMode(MissingKeyMode.RETURN_MISSING_MESSAGE_PREFIX)
            .placeholderStyles(
                listOf(PlaceholderStyle.MINIMESSAGE_TAG, PlaceholderStyle.POSITIONAL_PERCENT_INDEX),
            )
            .colorMode(ColorMode.MINIMESSAGE),
    )

    init {
        loadLanguages()
    }

    fun loadLanguages() {
        languageFiles.clear()
        defaultLanguage = plugin.config.getString("settings.default_language", "zh_CN") ?: "zh_CN"
        currentLanguage = defaultLanguage

        val languageDirectory = File(plugin.dataFolder, "lang")
        if (!languageDirectory.exists()) {
            languageDirectory.mkdirs()
        }
        val bundledLanguages = arrayOf("zh_CN", "zh_TW", "en_US", "de_DE", "es_ES", "nl_NL", "tr_TR")
        for (language in bundledLanguages) {
            saveDefaultLanguageFile(language)
        }

        val files = languageDirectory.listFiles { _, name -> name.endsWith(".yml") }
        if (files != null) {
            for (file in files) {
                val languageCode = file.name.replace(".yml", "")
                try {
                    languageFiles[languageCode] = YamlConfiguration.loadConfiguration(file)
                    plugin.logger.info("已加载语言文件: $languageCode")
                } catch (exception: Exception) {
                    plugin.logger.log(Level.WARNING, "加载语言文件失败: ${file.name}", exception)
                }
            }
        }

        if (!languageFiles.containsKey(defaultLanguage)) {
            try {
                plugin.getResource("lang/$defaultLanguage.yml")?.use { inputStream ->
                    val defaultConfig = YamlConfiguration.loadConfiguration(
                        InputStreamReader(inputStream, StandardCharsets.UTF_8),
                    )
                    languageFiles[defaultLanguage] = defaultConfig
                    plugin.logger.info("Loaded default language: $defaultLanguage")
                }
            } catch (exception: Exception) {
                plugin.logger.log(Level.WARNING, "Failed to load default language: $defaultLanguage", exception)
            }
        }
        i18n.setCurrentLocale(currentLanguage)
        i18n.reload()
    }

    private fun saveDefaultLanguageFile(languageCode: String) {
        val languageFile = File(plugin.dataFolder, "lang/$languageCode.yml")
        val resourcePath = "lang/$languageCode.yml"
        if (!languageFile.exists()) {
            plugin.saveResource(resourcePath, false)
        } else {
            LanguageUpdater.merge(plugin, languageFile, resourcePath)
        }
    }

    fun getMessage(key: String): String = getMessage(key, currentLanguage)

    fun getMessage(key: String, languageCode: String): String =
        MetroTextRenderer.renderPreservingPlaceholders(rawMessage(key, languageCode))

    fun getMessage(key: String, vararg arguments: Any?): String {
        val positional: MutableMap<String, Any?> = HashMap()
        for (index in arguments.indices) {
            positional["arg${index + 1}"] = arguments[index]
        }
        return MetroTextRenderer.render(rawMessage(key, currentLanguage), positional)
    }

    fun getMessage(key: String, namedArguments: Map<String, Any?>): String =
        MetroTextRenderer.render(rawMessage(key, currentLanguage), namedArguments)

    private fun rawMessage(key: String, languageCode: String): String {
        var languageConfig = languageFiles[languageCode]
        if (languageConfig == null || !languageConfig.contains(key)) {
            languageConfig = languageFiles[defaultLanguage]
        }
        if (languageConfig != null && languageConfig.contains(key)) {
            return languageConfig.getString(key, "") ?: ""
        }
        return "Missing message: $key"
    }

    companion object {
        @JvmStatic
        fun args(): MutableMap<String, Any?> = HashMap()

        @JvmStatic
        fun put(
            arguments: MutableMap<String, Any?>,
            key: String,
            value: Any?,
        ): MutableMap<String, Any?> {
            arguments[key] = value
            return arguments
        }
    }
}
