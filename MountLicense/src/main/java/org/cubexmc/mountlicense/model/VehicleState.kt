package org.cubexmc.mountlicense.model

import java.util.Locale

enum class VehicleState {
    ACTIVE,
    PARKED,
    LOCKED,
    RENTAL,
    MISSING,
    RELEASED;

    companion object {
        @JvmStatic
        fun fromString(raw: String?, fallback: VehicleState): VehicleState {
            if (raw == null) {
                return fallback
            }
            return try {
                valueOf(raw.uppercase(Locale.getDefault()))
            } catch (ex: IllegalArgumentException) {
                fallback
            }
        }
    }
}
