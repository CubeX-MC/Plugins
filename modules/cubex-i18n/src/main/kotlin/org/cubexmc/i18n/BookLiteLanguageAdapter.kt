package org.cubexmc.i18n

import org.bukkit.command.CommandSender

public open class BookLiteLanguageAdapter(private val i18n: I18nService) {
    public open fun setLocale(locale: String): Unit = i18n.setCurrentLocale(locale)
    public open fun load(): Unit = i18n.reload()
    public open fun raw(key: String?): String = i18n.raw(key)
    public open fun rawList(key: String?): List<String> = i18n.rawList(key)
    public open fun msg(key: String?): String = i18n.message(key)
    public open fun msg(key: String?, placeholders: Map<String, String>?): String = i18n.message(key, placeholders)
    public open fun msgList(key: String?, placeholders: Map<String, String>?): List<String> =
        i18n.messageList(key, placeholders)
    public open fun send(to: CommandSender?, key: String?): Unit = send(to, key, null)
    public open fun send(to: CommandSender?, key: String?, placeholders: Map<String, String>?) {
        if (to != null) to.sendMessage(msg(key, placeholders))
    }
}
