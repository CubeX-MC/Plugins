package org.cubexmc.metro.service

import java.util.Locale

enum class OperationMode {
    LOCAL,
    GLOBAL,
    ;

    companion object {
        @JvmStatic
        fun from(s: String?, def: OperationMode?): OperationMode? {
            if (s == null) return def
            return when (s.trim().lowercase(Locale.getDefault())) {
                "local" -> LOCAL
                "global" -> GLOBAL
                else -> def
            }
        }
    }
}
