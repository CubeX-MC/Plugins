package org.cubexmc.ecobalancer.tax

import java.util.Locale

enum class DebtMode {
    SKIP,
    DRAIN,
    ALLOW_NEGATIVE,
    INHERIT;

    fun toConfigValue(): String = name.lowercase(Locale.ROOT).replace('_', '-')

    companion object {
        @JvmStatic
        fun fromConfig(value: String?, fallback: DebtMode): DebtMode {
            if (value.isNullOrBlank()) {
                return fallback
            }
            return when (value.trim().lowercase(Locale.ROOT).replace('-', '_')) {
                "skip" -> SKIP
                "drain" -> DRAIN
                "allow_negative" -> ALLOW_NEGATIVE
                "inherit" -> INHERIT
                else -> fallback
            }
        }
    }
}
