package org.cubexmc.i18n

import java.util.EnumSet
import java.util.function.Supplier

class I18nOptions private constructor() {
    private var languageDirectoryValue = "lang"
    private var currentLocaleValue: String? = "zh_CN"
    private var currentLocaleSupplier: Supplier<String>? = null
    private var defaultLocaleValue: String? = "zh_CN"
    private val fallbackLocaleValues = ArrayList<String>()
    private val bundledLocaleValues = ArrayList<String>()
    private var prefixKeyValue = "prefix"
    private var prefixTokenValue = "%prefix%"
    private var keyPrefixValue = ""
    private var missingKeyModeValue = MissingKeyMode.RETURN_KEY
    private var warnOnMissingKeyValue = false
    private var placeholderStyleValues: Set<PlaceholderStyle> = EnumSet.of(PlaceholderStyle.PERCENT_NAME)
    private var colorModeValue: ColorMode? = ColorMode.LEGACY_AND_HEX

    fun languageDirectory(directory: String?): I18nOptions = apply {
        languageDirectoryValue = directory?.takeUnless { it.isBlank() } ?: "lang"
    }

    fun currentLocale(locale: String?): I18nOptions = apply {
        currentLocaleValue = locale
        currentLocaleSupplier = null
    }

    fun currentLocale(localeSupplier: Supplier<String>?): I18nOptions = apply {
        currentLocaleSupplier = localeSupplier
    }

    fun defaultLocale(locale: String?): I18nOptions = apply { defaultLocaleValue = locale }

    fun fallbackLocales(locales: List<String>?): I18nOptions = apply {
        fallbackLocaleValues.clear()
        if (locales != null) fallbackLocaleValues.addAll(locales)
    }

    fun bundledLocales(locales: Collection<String>?): I18nOptions = apply {
        bundledLocaleValues.clear()
        if (locales != null) bundledLocaleValues.addAll(locales)
    }

    fun prefixKey(key: String?): I18nOptions = apply { prefixKeyValue = key ?: "" }
    fun prefixToken(token: String?): I18nOptions = apply { prefixTokenValue = token ?: "" }
    fun keyPrefix(keyPrefix: String?): I18nOptions = apply { keyPrefixValue = keyPrefix ?: "" }
    fun missingKeyMode(mode: MissingKeyMode?): I18nOptions =
        apply { missingKeyModeValue = mode ?: MissingKeyMode.RETURN_KEY }
    fun warnOnMissingKey(enabled: Boolean): I18nOptions = apply { warnOnMissingKeyValue = enabled }

    fun placeholderStyles(styles: Collection<PlaceholderStyle>?): I18nOptions = apply {
        placeholderStyleValues =
            if (styles.isNullOrEmpty()) EnumSet.noneOf(PlaceholderStyle::class.java) else EnumSet.copyOf(styles)
    }

    fun colorize(enabled: Boolean): I18nOptions = apply {
        colorModeValue = if (enabled) ColorMode.LEGACY_AND_HEX else null
    }

    fun colorMode(mode: ColorMode?): I18nOptions = apply { colorModeValue = mode }

    internal fun languageDirectory(): String = languageDirectoryValue
    internal fun currentLocale(): String? =
        currentLocaleSupplier?.get()?.takeUnless { it.isBlank() } ?: currentLocaleValue
    internal fun hasCurrentLocaleSupplier(): Boolean = currentLocaleSupplier != null
    internal fun defaultLocale(): String? = defaultLocaleValue
    internal fun fallbackLocales(): List<String> = fallbackLocaleValues.toList()
    internal fun bundledLocales(): List<String> = bundledLocaleValues.toList()
    internal fun prefixKey(): String = prefixKeyValue
    internal fun prefixToken(): String = prefixTokenValue
    internal fun keyPrefix(): String = keyPrefixValue
    internal fun missingKeyMode(): MissingKeyMode = missingKeyModeValue
    internal fun warnOnMissingKey(): Boolean = warnOnMissingKeyValue
    internal fun placeholderStyles(): Set<PlaceholderStyle> = placeholderStyleValues.toSet()
    internal fun colorMode(): ColorMode? = colorModeValue

    companion object {
        @JvmStatic
        fun create(): I18nOptions = I18nOptions()
    }
}
