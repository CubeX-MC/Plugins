package org.cubexmc.booklite.lang

import java.io.File
import org.cubexmc.booklite.BookLitePlugin
import org.cubexmc.i18n.BookLiteLanguageAdapter
import org.cubexmc.i18n.ColorMode
import org.cubexmc.i18n.I18nOptions
import org.cubexmc.i18n.I18nServices
import org.cubexmc.i18n.MissingKeyMode
import org.cubexmc.i18n.PlaceholderStyle

class LanguageManager(
    private val plugin: BookLitePlugin,
    private var locale: String,
) : BookLiteLanguageAdapter(
    I18nServices.create(
        plugin,
        I18nOptions.create()
            .languageDirectory("lang")
            .currentLocale(locale)
            .defaultLocale("zh_CN")
            .fallbackLocales(listOf("zh_CN"))
            .bundledLocales(listOf("zh_CN", "en_US"))
            .prefixKey("prefix")
            .prefixToken("<prefix>")
            .missingKeyMode(MissingKeyMode.RETURN_KEY)
            .placeholderStyles(listOf(PlaceholderStyle.MINIMESSAGE_TAG))
            .colorMode(ColorMode.MINIMESSAGE),
    ),
) {
    override fun setLocale(locale: String) {
        this.locale = locale
        super.setLocale(locale)
    }

    override fun load() {
        val langFile = File(plugin.dataFolder, "lang" + File.separator + locale + ".yml")
        if (!langFile.exists()) {
            plugin.logger.warning("Language file $locale.yml missing, falling back to zh_CN.")
        }
        super.load()
    }
}
