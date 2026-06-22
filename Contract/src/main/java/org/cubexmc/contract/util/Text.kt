package org.cubexmc.contract.util

import net.md_5.bungee.api.ChatColor
import java.util.regex.Pattern

object Text {
    private val HEX_PATTERN: Pattern = Pattern.compile("&#([A-Fa-f0-9]{6})")

    @JvmStatic
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

    @JvmStatic
    fun stripControl(input: String?): String {
        if (input == null) {
            return ""
        }
        return input.replace("[\\p{Cntrl}&&[^\r\n\t]]".toRegex(), "").trim()
    }
}
