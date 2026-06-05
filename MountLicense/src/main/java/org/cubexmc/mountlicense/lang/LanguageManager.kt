package org.cubexmc.mountlicense.lang

import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cubexmc.i18n.ColorMode
import org.cubexmc.i18n.I18nOptions
import org.cubexmc.i18n.I18nService
import org.cubexmc.i18n.I18nServices
import org.cubexmc.i18n.MissingKeyMode
import org.cubexmc.i18n.PlaceholderStyle
import org.cubexmc.mountlicense.MountLicensePlugin
import java.io.File

class LanguageManager(
    private val plugin: MountLicensePlugin,
    private var locale: String,
) {
    private val i18n: I18nService = I18nServices.create(
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
    )

    fun setLocale(locale: String) {
        this.locale = locale
        i18n.setCurrentLocale(locale)
    }

    fun load() {
        val langFile = File(plugin.dataFolder, "lang" + File.separator + locale + ".yml")
        if (!langFile.exists() && locale != "zh_CN") {
            plugin.logger.warning("Language file $locale.yml missing, falling back to zh_CN.")
        }
        i18n.reload()
    }

    fun raw(key: String): String = i18n.raw(key)

    fun rawList(key: String): List<String> = i18n.rawList(key)

    fun msg(key: String, placeholders: Map<String, String>?): String = i18n.message(key, placeholders)

    fun msg(key: String): String = i18n.message(key)

    fun msgList(key: String, placeholders: Map<String, String>?): List<String> =
        i18n.messageList(key, placeholders)

    fun send(to: CommandSender?, key: String) {
        send(to, key, null)
    }

    fun send(to: CommandSender?, key: String, placeholders: Map<String, String>?) {
        if (to == null) return
        to.sendMessage(msg(key, placeholders))
    }

    fun sendActionBar(to: Player?, key: String) {
        sendActionBar(to, key, null)
    }

    fun sendActionBar(to: Player?, key: String, placeholders: Map<String, String>?) {
        if (to == null) return
        to.spigot().sendMessage(ChatMessageType.ACTION_BAR, *TextComponent.fromLegacyText(msg(key, placeholders)))
    }

    fun prefix(): String = msg("prefix")
}
