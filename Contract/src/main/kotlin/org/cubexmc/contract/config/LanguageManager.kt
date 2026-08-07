package org.cubexmc.contract.config

import org.cubexmc.contract.ContractPlugin
import org.cubexmc.contract.model.ContractStatus
import org.cubexmc.contract.model.ContractType
import org.cubexmc.contract.model.ObjectiveType
import org.cubexmc.contract.model.ParticipantRole
import org.cubexmc.contract.model.PayoutCondition
import org.cubexmc.core.Reloadable
import org.cubexmc.i18n.ColorMode
import org.cubexmc.i18n.I18nOptions
import org.cubexmc.i18n.I18nService
import org.cubexmc.i18n.I18nServices
import org.cubexmc.i18n.MissingKeyMode
import org.cubexmc.i18n.PlaceholderStyle
import java.util.Locale

/**
 * Contract's view of the shared [I18nService].
 *
 * Every locale section — `messages`, `ui`, the enum label sections — is served by one service
 * configured with an empty `keyPrefix`, so each section is addressed by its full key. That matters
 * for three reasons the earlier split (i18n for `messages.*`, a raw `YamlConfiguration` for
 * everything else) could not deliver:
 *
 * - a key missing from the active locale falls back down the locale chain to `zh_CN` instead of
 *   rendering as its own raw key name;
 * - one colour pipeline (MiniMessage) and one placeholder style (`<name>`) across the whole file;
 * - [MissingKeyMode] and missing-key warnings apply everywhere, not just to chat messages.
 */
class LanguageManager(private val plugin: ContractPlugin) : Reloadable {

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

    fun message(path: String, placeholders: Map<String, String>?): String =
        i18n.message("messages.$path", placeholders ?: emptyMap<String, String>())

    fun message(path: String): String = message(path, emptyMap<String, String>())

    fun ui(key: String, placeholders: Map<String, String> = emptyMap()): String =
        i18n.message("ui.$key", placeholders)

    fun status(status: ContractStatus): String = enumLabel("status", status.name)

    fun type(type: ContractType): String = enumLabel("types", type.name)

    fun role(role: ParticipantRole): String = enumLabel("roles", role.name)

    fun condition(condition: PayoutCondition): String = enumLabel("conditions", condition.name)

    fun objective(type: ObjectiveType): String = enumLabel("objectives", type.name)

    fun objectiveTarget(type: ObjectiveType?): String = enumLabel("objective-targets", type?.name ?: DEFAULT_ENUM_KEY)

    fun objectivePrompt(type: ObjectiveType?): String = enumLabel("objective-prompts", type?.name ?: DEFAULT_ENUM_KEY)

    /**
     * A `terms.*` phrase, falling back to [fallback] when the locale chain does not define it.
     * Uses [I18nService.rawOrNull] because [I18nService.raw] cannot distinguish "absent" from a
     * translation that happens to equal the key.
     */
    fun term(key: String, fallback: String): String {
        val path = "terms.$key"
        return if (i18n.rawOrNull(path) == null) fallback else i18n.message(path)
    }

    private fun enumLabel(section: String, name: String): String =
        i18n.message("$section." + name.lowercase(Locale.ROOT))

    private fun sanitizeLanguageName(configured: String?): String {
        if (configured.isNullOrBlank()) {
            return DEFAULT_LOCALE
        }
        val sanitized = configured.replace("[^A-Za-z0-9_-]".toRegex(), "")
        return if (sanitized.isBlank()) DEFAULT_LOCALE else sanitized
    }

    private companion object {
        const val DEFAULT_LOCALE = "zh_CN"
        const val DEFAULT_ENUM_KEY = "DEFAULT"
    }
}
