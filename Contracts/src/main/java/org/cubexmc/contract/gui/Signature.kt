package org.cubexmc.contract.gui

import java.util.Locale

object Signature {
    @JvmStatic
    fun matches(playerName: String?, input: String?): Boolean {
        if (playerName == null || input == null) {
            return false
        }
        val token = input.trim()
        if (token.isEmpty()) {
            return false
        }
        if (token.equals(playerName.trim(), ignoreCase = true)) {
            return true
        }
        val lower = token.lowercase(Locale.ROOT)
        return token == "同意" || lower == "agree" || lower == "confirm"
    }
}
