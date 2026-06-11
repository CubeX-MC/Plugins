package org.cubexmc.metro.util

import org.bukkit.NamespacedKey
import org.cubexmc.metro.Metro

/**
 * Shared constants used across runtime components.
 */
object MetroConstants {
    const val METRO_MINECART_NAME: String = "MetroMinecart"
    const val SCOREBOARD_OBJECTIVE: String = "metro"

    // PDC key for identifying metro minecarts
    private var minecartKey: NamespacedKey? = null

    @JvmStatic
    fun initialize(plugin: Metro) {
        if (minecartKey == null) {
            minecartKey = NamespacedKey(plugin, "is_metro")
        }
    }

    @JvmStatic
    fun getMinecartKey(): NamespacedKey? = minecartKey
}
