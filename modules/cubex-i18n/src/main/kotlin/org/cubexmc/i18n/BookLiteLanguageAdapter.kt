package org.cubexmc.i18n

import org.bukkit.command.CommandSender

open class BookLiteLanguageAdapter(private val i18n: I18nService) {
    open fun setLocale(locale: String) = i18n.setCurrentLocale(locale)
    open fun load() = i18n.reload()
    open fun raw(key: String?): String = i18n.raw(key)
    open fun rawList(key: String?): List<String> = i18n.rawList(key)
    open fun msg(key: String?): String = i18n.message(key)
    open fun msg(key: String?, placeholders: Map<String, String>?): String = i18n.message(key, placeholders)
    open fun msgList(key: String?, placeholders: Map<String, String>?): List<String> =
        i18n.messageList(key, placeholders)
    open fun send(to: CommandSender?, key: String?) = send(to, key, null)
    open fun send(to: CommandSender?, key: String?, placeholders: Map<String, String>?) {
        if (to != null) to.sendMessage(msg(key, placeholders))
    }
}
