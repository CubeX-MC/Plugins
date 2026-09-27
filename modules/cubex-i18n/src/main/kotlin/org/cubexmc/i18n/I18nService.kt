package org.cubexmc.i18n

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.cubexmc.core.Reloadable

public interface I18nService : Reloadable {
    public fun currentLocale(): String
    public fun setCurrentLocale(locale: String?)
    override fun reload()
    public fun raw(key: String?): String
    public fun raw(key: String?, locale: String?): String
    public fun rawOrNull(key: String?): String?
    public fun rawOrNull(key: String?, locale: String?): String?
    public fun rawList(key: String?): List<String>
    public fun rawList(key: String?, locale: String?): List<String>
    public fun message(key: String?): String
    public fun message(key: String?, placeholders: Map<String, *>?): String
    public fun message(key: String?, locale: String?, placeholders: Map<String, *>?): String
    public fun message(key: String?, vararg positionalArgs: Any?): String
    public fun messageList(key: String?, placeholders: Map<String, *>?): List<String>
    public fun messageList(key: String?, locale: String?, placeholders: Map<String, *>?): List<String>
    /** Renders a caller-owned template through the same prefix/placeholder/color pipeline. */
    public fun render(template: String?, placeholders: Map<String, *>?): String
    public fun component(key: String?): Component
    public fun component(key: String?, placeholders: Map<String, *>?): Component
    public fun component(key: String?, locale: String?, placeholders: Map<String, *>?): Component
    public fun componentList(key: String?, placeholders: Map<String, *>?): List<Component>
    public fun componentList(key: String?, locale: String?, placeholders: Map<String, *>?): List<Component>
    public fun componentOf(renderedMessage: String?): Component
    public fun send(sender: CommandSender?, key: String?, placeholders: Map<String, *>?)
    public fun send(sender: CommandSender?, key: String?, locale: String?, placeholders: Map<String, *>?)
}
