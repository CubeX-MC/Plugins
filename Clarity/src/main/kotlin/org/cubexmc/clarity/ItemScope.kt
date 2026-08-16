package org.cubexmc.clarity

import java.util.Locale

/** Player item containers that Clarity can scan or clean. */
enum class ItemScope(private val id: String) {
    HAND("hand"),
    INVENTORY("inventory"),
    EQUIPMENT("equipment"),
    ENDER("ender"),
    ALL("all"),
    ;

    fun id(): String = id

    companion object {
        @JvmStatic
        fun isScope(raw: String?): Boolean = parse(raw) != null

        @JvmStatic
        fun parse(raw: String?): ItemScope? {
            val value = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.id == value }
        }
    }
}
