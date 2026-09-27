package org.cubexmc.core

import java.util.regex.Pattern
import net.md_5.bungee.api.ChatColor

public class CubexText {
    public fun color(input: String?): String = colorOrNull(input) ?: ""

    public fun colorOrNull(input: String?): String? {
        if (input == null || input.isEmpty()) return input

        val matcher = HEX_PATTERN.matcher(input)
        val buffer = StringBuffer()
        while (matcher.find()) {
            val replacement = try {
                ChatColor.of("#${matcher.group(1)}").toString()
            } catch (_: NoSuchMethodError) {
                ""
            } catch (_: NoClassDefFoundError) {
                ""
            }
            matcher.appendReplacement(buffer, replacement)
        }
        matcher.appendTail(buffer)
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', buffer.toString())
    }

    public fun stripControl(input: String?): String =
        input?.replace(CONTROL_PATTERN, "")?.trim() ?: ""

    public fun nullToEmpty(input: String?): String = input ?: ""

    public companion object {
        private val SHARED = CubexText()
        private val HEX_PATTERN: Pattern = Pattern.compile("&#([A-Fa-f0-9]{6})")
        private val CONTROL_PATTERN = Regex("[\\p{Cntrl}&&[^\\r\\n\\t]]")

        @JvmStatic
        public fun translateColorCodes(input: String?): String? = SHARED.colorOrNull(input)
    }
}
