package org.cubexmc.contract.model

import java.util.Locale

enum class BatchRepeatPolicy {
    UNLIMITED,
    ONCE,
    COOLDOWN,
    ;

    companion object {
        @JvmStatic
        fun fromStored(value: String?): BatchRepeatPolicy =
            try {
                if (value.isNullOrBlank()) UNLIMITED else valueOf(value.uppercase(Locale.ROOT))
            } catch (ex: IllegalArgumentException) {
                UNLIMITED
            }
    }
}
