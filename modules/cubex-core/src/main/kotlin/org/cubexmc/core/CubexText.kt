package org.cubexmc.core

import java.util.regex.Pattern
import net.md_5.bungee.api.ChatColor

class CubexText {
    fun color(input: String?): String = colorOrNull(input) ?: ""

    fun colorOrNull(input: String?): String? {
        if (input == null || input.isEmpty()) return input

        val matcher = HEX_PATTERN.matcher(input)
        val buffer = StringBuffer()
        while (matcher.find()) {
            matcher.appendReplacement(buffer, ChatColor.of("#${matcher.group(1)}").toString())
        }
        matcher.appendTail(buffer)
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', buffer.toString())
    }

    fun stripControl(input: String?): String =
        input?.replace(CONTROL_PATTERN, "")?.trim() ?: ""

    fun nullToEmpty(input: String?): String = input ?: ""

    private companion object {
        val HEX_PATTERN: Pattern = Pattern.compile("&#([A-Fa-f0-9]{6})")
        val CONTROL_PATTERN = Regex("[\\p{Cntrl}&&[^\\r\\n\\t]]")
    }
}
