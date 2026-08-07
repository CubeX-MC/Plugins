package org.cubexmc.regions.config

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.cubexmc.core.Reloadable
import org.cubexmc.i18n.ColorMode
import org.cubexmc.i18n.I18nOptions
import org.cubexmc.i18n.I18nService
import org.cubexmc.i18n.I18nServices
import org.cubexmc.i18n.MissingKeyMode
import org.cubexmc.i18n.PlaceholderStyle
import org.cubexmc.regions.RegionsPlugin

/**
 * Regions' view of the shared [I18nService].
 *
 * One service with an empty `keyPrefix` serves every section — `gui.*`, `game.*`, the top-level
 * command messages — so each is addressed by its full key. Compared with the hand-rolled
 * [org.bukkit.configuration.file.YamlConfiguration] this replaced, that buys three things: a key
 * missing from the active locale falls back down the chain to `zh_CN` instead of rendering as its
 * own key path; one colour pipeline (MiniMessage) and one placeholder style (`<name>`) across the
 * whole file; and [MissingKeyMode] applies everywhere rather than only to chat.
 */
class LanguageManager(private val plugin: RegionsPlugin) : Reloadable {

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

    fun message(key: String, placeholders: Map<String, String> = emptyMap()): String =
        i18n.message(key, placeholders)

    fun component(key: String, placeholders: Map<String, String> = emptyMap()): Component =
        i18n.component(key, placeholders)

    /**
     * Reads a key that holds a list of lines, used for item lore. A plain string is accepted too and
     * split on newlines, so a translator can collapse a short lore block into one scalar.
     */
    fun messageList(key: String, placeholders: Map<String, String> = emptyMap()): List<String> {
        val lines = i18n.messageList(key, placeholders)
        if (lines.isNotEmpty()) return lines
        if (i18n.rawOrNull(key) == null) return emptyList()
        return message(key, placeholders).split(NEWLINE)
    }

    fun has(key: String): Boolean = i18n.rawOrNull(key) != null

    /**
     * Parses text that is already rendered — the output of [message], possibly concatenated with
     * more of it — or that an operator wrote in `&`-code form in `regions.yml` / `templates.yml`.
     *
     * Trigger actions and GUI labels both need this: they assemble a line out of several fragments,
     * so they cannot use [component], but they still need a component at the display boundary.
     * Colouring first means one function covers both the `§` the service emits and the `&` an
     * operator types.
     */
    fun render(rendered: String): Component = i18n.componentOf(plugin.text().color(rendered))

    fun prefixed(key: String, placeholders: Map<String, String> = emptyMap()): String =
        message("prefix") + message(key, placeholders)

    fun send(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        sendRaw(sender, prefixed(key, placeholders))
    }

    /** Sends a translated message without the plugin prefix, for multi-line command output. */
    fun sendPlain(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        sendRaw(sender, message(key, placeholders))
    }

    fun sendRaw(sender: CommandSender, raw: String) {
        for (line in raw.split(NEWLINE)) {
            sender.sendMessage(render(line))
        }
    }

    private fun sanitizeLanguageName(configured: String?): String {
        if (configured.isNullOrBlank()) return DEFAULT_LOCALE
        val sanitized = configured.replace("[^A-Za-z0-9_-]".toRegex(), "")
        return if (sanitized.isBlank()) DEFAULT_LOCALE else sanitized
    }

    private companion object {
        const val DEFAULT_LOCALE = "zh_CN"
        val NEWLINE = Regex("\\R")
    }
}
