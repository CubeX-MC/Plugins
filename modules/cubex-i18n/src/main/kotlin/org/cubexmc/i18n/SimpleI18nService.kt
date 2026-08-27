package org.cubexmc.i18n

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.core.CubexPlugin
import org.cubexmc.core.CubexText
import java.io.File
import java.util.LinkedHashSet
import java.util.regex.Pattern

internal class SimpleI18nService(
    private val plugin: CubexPlugin,
    private val options: I18nOptions,
) : I18nService {
    private val text = CubexText()
    private val miniMessage = MiniMessage.miniMessage()
    private val legacySerializer =
        LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build()
    private val languages = HashMap<String, YamlConfiguration>()
    private var locale: String? = options.currentLocale()
    private var prefix = ""
    private var prefixTemplate = ""

    override fun currentLocale(): String = locale.orEmpty()

    override fun setCurrentLocale(locale: String?) {
        this.locale = locale
    }

    override fun reload() {
        languages.clear()
        if (options.hasCurrentLocaleSupplier() || locale.isNullOrBlank()) locale = options.currentLocale()
        val activeLocale = firstAvailableLocale(locale)
        prefixTemplate = localeChain(activeLocale)
            .firstNotNullOfOrNull { configuration(it).getString(options.prefixKey()) }.orEmpty()
        prefix = color(prefixTemplate)
    }

    override fun raw(key: String?): String = raw(key, locale)

    override fun rawOrNull(key: String?): String? = rawOrNull(key, locale)

    override fun rawOrNull(key: String?, locale: String?): String? = lookup(locale, key)

    override fun raw(key: String?, locale: String?): String = lookup(locale, key) ?: missing(key)

    override fun rawList(key: String?): List<String> = rawList(key, locale)

    override fun rawList(key: String?, locale: String?): List<String> {
        val resolvedKey = resolveKey(key)
        for (candidate in localeChain(locale)) {
            val configuration = configuration(candidate)
            if (configuration.isList(resolvedKey)) return configuration.getStringList(resolvedKey)
        }
        return emptyList()
    }

    override fun message(key: String?): String = message(key, emptyMap<String, Any?>())

    override fun message(key: String?, placeholders: Map<String, *>?): String = message(key, locale, placeholders)

    override fun message(key: String?, locale: String?, placeholders: Map<String, *>?): String =
        format(raw(key, locale), placeholders)

    override fun message(key: String?, vararg positionalArgs: Any?): String {
        var rendered = raw(key)
        if (options.placeholderStyles().contains(PlaceholderStyle.POSITIONAL_PERCENT_INDEX)) {
            positionalArgs.forEachIndexed { index, argument ->
                rendered = rendered.replace("%${index + 1}", value(argument))
            }
        }
        return format(rendered, emptyMap<String, Any?>())
    }

    override fun messageList(key: String?, placeholders: Map<String, *>?): List<String> =
        rawList(key).map { format(it, placeholders) }

    override fun render(template: String?, placeholders: Map<String, *>?): String = format(template, placeholders)

    override fun component(key: String?): Component = component(key, emptyMap<String, Any?>())

    override fun component(key: String?, placeholders: Map<String, *>?): Component =
        if (options.colorMode() == ColorMode.MINIMESSAGE) {
            deserializeMiniMessage(raw(key, locale), placeholders)
        } else {
            componentOf(message(key, placeholders))
        }

    override fun componentList(key: String?, placeholders: Map<String, *>?): List<Component> =
        rawList(key).map { line ->
            if (options.colorMode() == ColorMode.MINIMESSAGE) deserializeMiniMessage(line, placeholders)
            else componentOf(format(line, placeholders))
        }

    override fun componentOf(renderedMessage: String?): Component = legacySerializer.deserialize(renderedMessage.orEmpty())

    override fun send(sender: CommandSender?, key: String?, placeholders: Map<String, *>?) {
        if (sender != null) sender.sendMessage(message(key, placeholders))
    }

    private fun lookup(locale: String?, key: String?): String? {
        val resolvedKey = resolveKey(key)
        for (candidate in localeChain(locale)) {
            configuration(candidate).getString(resolvedKey)?.let { return it }
        }
        return null
    }

    private fun firstAvailableLocale(preferred: String?): String? {
        for (candidate in localeChain(preferred)) {
            if (languageFile(candidate).exists() || plugin.getResource(resourcePath(candidate)) != null) return candidate
        }
        return preferred
    }

    private fun localeChain(preferred: String?): List<String> {
        val chain = LinkedHashSet<String>()
        addLocale(chain, preferred)
        addLocale(chain, options.defaultLocale())
        options.fallbackLocales().forEach { addLocale(chain, it) }
        options.bundledLocales().forEach { addLocale(chain, it) }
        return chain.toList()
    }

    private fun addLocale(locales: MutableSet<String>, locale: String?) {
        if (!locale.isNullOrBlank()) locales.add(locale)
    }

    private fun configuration(locale: String?): YamlConfiguration {
        if (locale == null) return YamlConfiguration()
        return languages.computeIfAbsent(locale, ::loadConfiguration)
    }

    private fun loadConfiguration(locale: String): YamlConfiguration {
        val file = languageFile(locale)
        return if (file.exists()) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
    }

    private fun languageFile(locale: String): File = File(plugin.dataFolder, resourcePath(locale))

    private fun resourcePath(locale: String): String = options.languageDirectory() + File.separator + locale + ".yml"

    private fun resolveKey(key: String?): String {
        if (key == null) return ""
        val keyPrefix = options.keyPrefix()
        return if (keyPrefix.isEmpty() || key.startsWith(keyPrefix)) key else keyPrefix + key
    }

    private fun missing(key: String?): String {
        if (options.warnOnMissingKey()) plugin.logger.warning("Missing message: $key")
        return when (options.missingKeyMode()) {
            MissingKeyMode.RETURN_EMPTY -> ""
            MissingKeyMode.RETURN_MISSING_MESSAGE_PREFIX -> "Missing message: $key"
            else -> key.orEmpty()
        }
    }

    private fun format(raw: String?, placeholders: Map<String, *>?): String {
        if (raw == null) return ""
        if (options.colorMode() == ColorMode.MINIMESSAGE) return renderMiniMessage(raw, placeholders)
        var formatted: String = raw
        if (options.prefixToken().isNotEmpty()) formatted = formatted.replace(options.prefixToken(), prefix)
        placeholders?.forEach { (key, argument) ->
            val value = value(argument)
            if (options.placeholderStyles().contains(PlaceholderStyle.PERCENT_NAME)) {
                formatted = formatted.replace("%$key%", value)
            }
            if (options.placeholderStyles().contains(PlaceholderStyle.BRACE_NAME)) {
                formatted = formatted.replace("{$key}", value)
            }
        }
        return color(formatted)
    }

    private fun color(input: String?): String = when (options.colorMode()) {
        null -> input.orEmpty()
        ColorMode.MINIMESSAGE -> renderMiniMessage(input, emptyMap<String, Any?>())
        ColorMode.LEGACY_AND_HEX -> text.color(input)
    }

    private fun renderMiniMessage(template: String?, placeholders: Map<String, *>?): String =
        legacySerializer.serialize(deserializeMiniMessage(template, placeholders))

    private fun deserializeMiniMessage(template: String?, placeholders: Map<String, *>?): Component {
        var resolvedTemplate = template.orEmpty()
        if (options.prefixToken().isNotEmpty()) {
            resolvedTemplate = resolvedTemplate.replace(options.prefixToken(), prefixTemplate)
        }
        return miniMessage.deserialize(resolvedTemplate, buildResolver(placeholders))
    }

    private fun buildResolver(placeholders: Map<String, *>?): TagResolver {
        val builder = TagResolver.builder()
        if (placeholders != null && options.placeholderStyles().contains(PlaceholderStyle.MINIMESSAGE_TAG)) {
            placeholders.forEach { (name, argument) ->
                if (MINIMESSAGE_PLACEHOLDER_NAME.matcher(name).matches()) {
                    builder.resolver(Placeholder.unparsed(name, value(argument)))
                }
            }
        }
        return builder.build()
    }

    private fun value(value: Any?): String = value?.toString().orEmpty()

    private companion object {
        val MINIMESSAGE_PLACEHOLDER_NAME: Pattern = Pattern.compile("[a-z0-9_:-]+")
    }
}
