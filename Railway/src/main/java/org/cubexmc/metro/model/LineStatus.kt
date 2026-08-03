package org.cubexmc.metro.model

import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Represents the operational status of a metro line.
 */
enum class LineStatus {
    /** Line is operating normally */
    NORMAL,

    /** Line is temporarily suspended (e.g., maintenance, incident) */
    SUSPENDED,

    /** Line is under maintenance (banners shown, may still operate) */
    MAINTENANCE,
    ;

    fun isBoardable(): Boolean = this == NORMAL || this == MAINTENANCE

    fun getConfigKey(): String = name.lowercase(Locale.ROOT)

    companion object {
        @JvmStatic
        fun fromConfig(value: String?): LineStatus {
            if (value == null) return NORMAL
            val normalized = value.trim()
            if (normalized.isEmpty()) return NORMAL
            return try {
                valueOf(normalized.uppercase(Locale.ROOT))
            } catch (e: IllegalArgumentException) {
                Logger.getGlobal().log(Level.WARNING, "Invalid line status in config: '$value', defaulting to NORMAL")
                NORMAL
            }
        }
    }
}
