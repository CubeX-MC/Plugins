package org.cubexmc.metro.model

import java.util.Locale

enum class DirectionMode {
    BI_DIRECTIONAL,
    CIRCULAR,
    SINGLE_DIRECTION,
    ;

    companion object {
        @JvmStatic
        fun from(s: String?, def: DirectionMode): DirectionMode {
            if (s == null) {
                return def
            }
            return try {
                valueOf(s.uppercase(Locale.getDefault()))
            } catch (_: IllegalArgumentException) {
                def
            }
        }
    }
}
