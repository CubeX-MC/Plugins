package org.cubexmc.i18n

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.cubexmc.core.Reloadable

interface I18nService : Reloadable {
    fun currentLocale(): String
    fun setCurrentLocale(locale: String?)
    override fun reload()
    fun raw(key: String?): String
    fun raw(key: String?, locale: String?): String
    fun rawOrNull(key: String?): String?
    fun rawOrNull(key: String?, locale: String?): String?
    fun rawList(key: String?): List<String>
    fun rawList(key: String?, locale: String?): List<String>
    fun message(key: String?): String
    fun message(key: String?, placeholders: Map<String, *>?): String
    fun message(key: String?, locale: String?, placeholders: Map<String, *>?): String
    fun message(key: String?, vararg positionalArgs: Any?): String
    fun messageList(key: String?, placeholders: Map<String, *>?): List<String>
    fun component(key: String?): Component
    fun component(key: String?, placeholders: Map<String, *>?): Component
    fun componentList(key: String?, placeholders: Map<String, *>?): List<Component>
    fun componentOf(renderedMessage: String?): Component
    fun send(sender: CommandSender?, key: String?, placeholders: Map<String, *>?)
}
