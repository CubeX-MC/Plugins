package org.cubexmc.metro.control

import java.util.Locale

enum class TrainControlMode {
    KINEMATIC,
    LEASHED,
    REACTIVE,
    ;

    companion object {
        @JvmStatic
        fun from(raw: String?, fallback: TrainControlMode?): TrainControlMode? {
            if (raw == null) {
                return fallback
            }
            val v = raw.trim().uppercase(Locale.getDefault())
            for (mode in values()) {
                if (mode.name == v) {
                    return mode
                }
            }
            // also accept human-friendly strings
            if ("KINEMATIC".equals(raw, ignoreCase = true)) return KINEMATIC
            if ("LEASHED".equals(raw, ignoreCase = true)) return LEASHED
            if ("REACTIVE".equals(raw, ignoreCase = true)) return REACTIVE
            return fallback
        }
    }
}
