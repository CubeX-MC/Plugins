package org.cubexmc.reputations.util

import net.md_5.bungee.api.ChatColor
import java.util.regex.Pattern

/** Translates `&` codes and `&#RRGGBB` hex into section-coded strings. */
object Colors {
    private val HEX_PATTERN: Pattern = Pattern.compile("&#([A-Fa-f0-9]{6})")

    fun color(input: String?): String {
        if (input.isNullOrEmpty()) {
            return ""
        }
        val matcher = HEX_PATTERN.matcher(input)
        val buffer = StringBuffer()
        while (matcher.find()) {
            matcher.appendReplacement(buffer, ChatColor.of("#" + matcher.group(1)).toString())
        }
        matcher.appendTail(buffer)
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', buffer.toString())
    }
}
