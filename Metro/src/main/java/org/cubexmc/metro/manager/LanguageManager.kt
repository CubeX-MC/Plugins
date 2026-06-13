package org.cubexmc.metro.manager

import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.logging.Level
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

/**
 * 管理多语言消息的类
 */
class LanguageManager(private val plugin: Metro) {
    private val i18n: I18nService
    private val languageFiles: MutableMap<String, YamlConfiguration> = HashMap()
    private var defaultLanguage = "zh_CN"
    private var currentLanguage = "zh_CN"

    init {
        i18n = I18nServices.create(
            plugin,
            I18nOptions.create()
                .languageDirectory("lang")
                .currentLocale { currentLanguage }
                .defaultLocale(defaultLanguage)
                .fallbackLocales(listOf("en_US", "zh_CN"))
                .bundledLocales(MetroMigrations.BUNDLED_LANGUAGES)
                .prefixToken("<prefix>")
                .missingKeyMode(MissingKeyMode.RETURN_MISSING_MESSAGE_PREFIX)
                .placeholderStyles(listOf(PlaceholderStyle.MINIMESSAGE_TAG, PlaceholderStyle.POSITIONAL_PERCENT_INDEX))
                .colorMode(ColorMode.MINIMESSAGE),
        )
        loadLanguages()
    }

    /**
     * 加载所有语言文件
     */
    fun loadLanguages() {
        languageFiles.clear()

        defaultLanguage = plugin.config.getString("settings.default_language", "zh_CN") ?: "zh_CN"
        currentLanguage = defaultLanguage

        val langDir = File(plugin.dataFolder, "lang")
        if (!langDir.exists()) {
            langDir.mkdirs()
        }

        val bundledLanguages = arrayOf("zh_CN", "zh_TW", "en_US", "de_DE", "es_ES", "nl_NL", "tr_TR")
        for (lang in bundledLanguages) {
            saveDefaultLanguageFile(lang)
        }

        val langFiles = langDir.listFiles { _, name -> name.endsWith(".yml") }
        if (langFiles != null) {
            for (file in langFiles) {
                val langCode = file.name.replace(".yml", "")
                try {
                    val langConfig = YamlConfiguration.loadConfiguration(file)
                    languageFiles[langCode] = langConfig
                    plugin.logger.info("已加载语言文件: $langCode")
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

    /**
     * 保存默认语言文件并自动合并新的语言键
     *
     * @param langCode 语言代码
     */
    private fun saveDefaultLanguageFile(langCode: String) {
        val langFile = File(plugin.dataFolder, "lang/$langCode.yml")
        val resourcePath = "lang/$langCode.yml"

        if (!langFile.exists()) {
            plugin.saveResource(resourcePath, false)
        } else {
            LanguageUpdater.merge(plugin, langFile, resourcePath)
        }
    }

    /**
     * 获取语言消息
     *
     * @param key 消息键
     * @return 格式化后的消息
     */
    fun getMessage(key: String): String = getMessage(key, currentLanguage)

    /**
     * 获取语言消息
     *
     * @param key 消息键
     * @param langCode 语言代码
     * @return 格式化后的消息
     */
    fun getMessage(key: String, langCode: String): String =
        MetroTextRenderer.renderPreservingPlaceholders(rawMessage(key, langCode))

    /**
     * 使用参数获取格式化的语言消息（数字占位符）
     *
     * @param key 消息键
     * @param args 替换参数，格式为 %1, %2 等
     * @return 格式化后的消息
     */
    fun getMessage(key: String, vararg args: Any?): String {
        val message = rawMessage(key, currentLanguage)
        val positional: MutableMap<String, Any?> = HashMap()
        for (index in args.indices) {
            positional["arg${index + 1}"] = args[index]
        }
        return MetroTextRenderer.render(message, positional)
    }

    /**
     * 使用命名参数获取格式化的语言消息
     *
     * @param key 消息键
     * @param namedArgs 命名参数映射，格式为 {key} = value
     * @return 格式化后的消息
     */
    fun getMessage(key: String, namedArgs: Map<String, Any?>): String =
        MetroTextRenderer.render(rawMessage(key, currentLanguage), namedArgs)

    private fun rawMessage(key: String, langCode: String): String {
        var langConfig = languageFiles[langCode]
        if (langConfig == null || !langConfig.contains(key)) {
            langConfig = languageFiles[defaultLanguage]
        }
        if (langConfig != null && langConfig.contains(key)) {
            return langConfig.getString(key, "") ?: ""
        }
        return "Missing message: $key"
    }

    companion object {
        /**
         * 创建命名参数映射的便捷方法
         *
         * @return 新的参数映射
         */
        @JvmStatic
        fun args(): MutableMap<String, Any?> = HashMap()

        /**
         * 向参数映射添加参数的便捷方法
         *
         * @param args 参数映射
         * @param key 参数名
         * @param value 参数值
         * @return 参数映射（链式调用）
         */
        @JvmStatic
        fun put(args: MutableMap<String, Any?>, key: String, value: Any?): MutableMap<String, Any?> {
            args[key] = value
            return args
        }
    }
}
