package org.cubexmc.metro.util

import java.util.regex.Pattern
import net.md_5.bungee.api.ChatColor

/**
 * 颜色解析工具类
 */
object ColorUtil {
    // 匹配 &#RRGGBB 格式的十六进制颜色代码
    private val HEX_PATTERN: Pattern = Pattern.compile("&#([A-Fa-f0-9]{6})")

    /**
     * 将包含颜色代码的字符串转换为带颜色的文本
     * 支持传统的 `&0`-`&f` 以及 `&#RRGGBB` 的十六进制颜色
     *
     * @param text 原始字符串
     * @return 转换颜色后的字符串
     */
    @JvmStatic
    fun colorize(text: String?): String? {
        if (text == null || text.isEmpty()) {
            return text
        }

        val matcher = HEX_PATTERN.matcher(text)
        val buffer = StringBuffer()

        while (matcher.find()) {
            val hex = "#" + matcher.group(1)
            matcher.appendReplacement(buffer, ChatColor.of(hex).toString())
        }
        matcher.appendTail(buffer)

        // 处理传统的 & 颜色代码
        return ChatColor.translateAlternateColorCodes('&', buffer.toString())
    }

    @JvmStatic
    fun colorizeOrEmpty(text: String?): String = colorize(text) ?: ""
}
