package org.cubexmc.fawereplace

import java.io.File
import java.util.logging.Logger
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.core.CubexPlugin
import org.cubexmc.i18n.ColorMode
import org.cubexmc.i18n.I18nOptions
import org.cubexmc.i18n.I18nService
import org.cubexmc.i18n.I18nServices
import org.cubexmc.i18n.MissingKeyMode
import org.cubexmc.i18n.PlaceholderStyle

/**
 * 多语言管理器
 * 负责加载和管理插件的多语言文本
 */
class LanguageManager(
    private val plugin: CubexPlugin,
    language: String,
) {
    private val logger: Logger = plugin.logger
    private val i18n: I18nService = I18nServices.create(
        plugin,
        I18nOptions.create()
            .languageDirectory("lang")
            .currentLocale(language)
            .defaultLocale("zh_CN")
            .fallbackLocales(listOf("zh_CN"))
            .bundledLocales(listOf("zh_CN", "en_US"))
            .prefixKey("prefix")
            .prefixToken("<prefix>")
            .missingKeyMode(MissingKeyMode.RETURN_KEY)
            .placeholderStyles(listOf(PlaceholderStyle.MINIMESSAGE_TAG))
            .colorMode(ColorMode.MINIMESSAGE),
    )
    private var currentLanguage: String = language

    init {
        loadLanguage(language)
    }

    /**
     * 加载指定语言文件
     *
     * @param language 语言代码
     */
    fun loadLanguage(language: String) {
        currentLanguage = language
        i18n.setCurrentLocale(language)

        val langFile = File(plugin.dataFolder, "lang/$language.yml")

        // 加载语言文件
        try {
            if (!langFile.exists() && language != "zh_CN") {
                logger.warning(getMessage("log.language_fallback", "file", "$language.yml"))
            }
            i18n.reload()
            val count = countMessages(language)
            logger.info(getMessage("log.language_loaded", "language", language, "count", count.toString()))
        } catch (exception: Exception) {
            logger.severe("Failed to load language file: $language.yml")
            exception.printStackTrace()
        }
    }

    /**
     * 获取翻译文本
     *
     * @param key 消息键
     * @return 翻译后的文本，如果键不存在则返回键本身
     */
    fun getMessage(key: String): String = i18n.message(key)

    /**
     * 获取翻译文本并替换占位符
     *
     * @param key 消息键
     * @param replacements 替换内容，格式为 {placeholder1, value1, placeholder2, value2, ...}
     * @return 翻译并替换后的文本
     */
    fun getMessage(key: String, vararg replacements: Any?): String {
        if (replacements.size % 2 != 0) {
            logger.warning(getMessage("log.invalid_replacements", "key", key))
            return getMessage(key)
        }
        val placeholders = HashMap<String, Any?>()
        var index = 0
        while (index < replacements.size) {
            val placeholder = replacements[index].toString()
            placeholders[placeholder] = replacements[index + 1]
            index += 2
        }
        return i18n.message(key, placeholders)
    }

    /**
     * 重新加载当前语言
     */
    fun reload() {
        loadLanguage(currentLanguage)
    }

    /**
     * 重新加载并切换到新语言
     *
     * @param language 新的语言代码
     */
    fun reload(language: String) {
        loadLanguage(language)
    }

    /**
     * 获取当前语言代码
     *
     * @return 当前语言代码
     */
    fun getCurrentLanguage(): String = currentLanguage

    private fun countMessages(language: String): Int {
        var langFile = File(plugin.dataFolder, "lang/$language.yml")
        if (!langFile.exists() && language != "zh_CN") {
            langFile = File(plugin.dataFolder, "lang/zh_CN.yml")
        }
        if (!langFile.exists()) {
            return 0
        }
        val config = YamlConfiguration.loadConfiguration(langFile)
        var count = 0
        for (key in config.getKeys(true)) {
            if (config.isString(key)) {
                count++
            }
        }
        return count
    }
}
