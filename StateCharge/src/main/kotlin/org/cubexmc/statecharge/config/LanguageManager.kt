package org.cubexmc.statecharge.config

import org.cubexmc.core.Reloadable
import org.cubexmc.i18n.ColorMode
import org.cubexmc.i18n.I18nOptions
import org.cubexmc.i18n.I18nService
import org.cubexmc.i18n.I18nServices
import org.cubexmc.i18n.MissingKeyMode
import org.cubexmc.i18n.PlaceholderStyle
import org.cubexmc.statecharge.StateChargePlugin

/**
 * StateCharge 对共享 [I18nService] 的视图(照抄 Contract 的 LanguageManager):
 * 空 keyPrefix,全文件按完整 key 寻址;MiniMessage 渲染成 legacy § 输出,`sendMessage(String)` 直接可用。
 */
class LanguageManager(private val plugin: StateChargePlugin) : Reloadable {

    private val i18n: I18nService = I18nServices.create(
        plugin,
        I18nOptions.create()
            .languageDirectory("lang")
            .currentLocale { sanitizeLanguageName(plugin.config.getString("language", DEFAULT_LOCALE)) }
            .defaultLocale(DEFAULT_LOCALE)
            .fallbackLocales(listOf(DEFAULT_LOCALE))
            .bundledLocales(listOf(DEFAULT_LOCALE, "en_US"))
            .prefixKey("prefix")
            .prefixToken("<prefix>")
            // Empty on purpose: sections are addressed by full key, so one service covers them all.
            .keyPrefix("")
            .missingKeyMode(MissingKeyMode.RETURN_KEY)
            .placeholderStyles(listOf(PlaceholderStyle.MINIMESSAGE_TAG))
            .colorMode(ColorMode.MINIMESSAGE),
    )

    override fun reload() {
        i18n.reload()
    }

    /** Alias kept for the plugin lifecycle's existing call sites. */
    fun load() = reload()

    /** A `messages.<path>` chat message. */
    fun message(path: String, placeholders: Map<String, String> = emptyMap()): String =
        i18n.message("messages.$path", placeholders)

    /** A `ui.<key>` command-rendering line. */
    fun ui(key: String, placeholders: Map<String, String> = emptyMap()): String =
        i18n.message("ui.$key", placeholders)

    private fun sanitizeLanguageName(configured: String?): String {
        if (configured.isNullOrBlank()) {
            return DEFAULT_LOCALE
        }
        val sanitized = configured.replace("[^A-Za-z0-9_-]".toRegex(), "")
        return if (sanitized.isBlank()) DEFAULT_LOCALE else sanitized
    }

    private companion object {
        const val DEFAULT_LOCALE = "zh_CN"
    }
}
