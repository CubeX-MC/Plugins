package org.cubexmc.metro.integration

import java.util.Locale
import java.util.regex.Pattern

/**
 * RGB color used by web map integrations.
 */
data class MapLineColor(
    private val red: Int,
    private val green: Int,
    private val blue: Int,
) {
    fun red(): Int = red

    fun green(): Int = green

    fun blue(): Int = blue

    fun asRgbInt(): Int = (red shl 16) or (green shl 8) or blue

    companion object {
        @JvmField
        val WHITE = MapLineColor(255, 255, 255)

        private val HEX_PATTERN: Pattern = Pattern.compile("(?i)&#([0-9a-f]{6})")
        private val BUKKIT_HEX_PATTERN: Pattern = Pattern.compile(
            "(?i)\u00a7x\u00a7([0-9a-f])\u00a7([0-9a-f])\u00a7([0-9a-f])" +
                "\u00a7([0-9a-f])\u00a7([0-9a-f])\u00a7([0-9a-f])",
        )

        @JvmStatic
        fun fromLineColor(color: String?): MapLineColor {
            if (color == null || color.isBlank()) {
                return WHITE
            }

            val hexMatcher = HEX_PATTERN.matcher(color)
            if (hexMatcher.find()) {
                return fromHex(hexMatcher.group(1))
            }

            val bukkitHexMatcher = BUKKIT_HEX_PATTERN.matcher(color)
            if (bukkitHexMatcher.find()) {
                val hex = StringBuilder(6)
                for (index in 1..6) {
                    hex.append(bukkitHexMatcher.group(index))
                }
                return fromHex(hex.toString())
            }

            val normalized = color.replace('\u00a7', '&').lowercase(Locale.ROOT)
            for (index in normalized.length - 2 downTo 0) {
                if (normalized[index] == '&') {
                    val legacyColor = fromLegacyCode(normalized[index + 1])
                    if (legacyColor != null) {
                        return legacyColor
                    }
                }
            }
            return WHITE
        }

        private fun fromHex(hex: String): MapLineColor {
            val rgb = hex.toInt(16)
            return MapLineColor((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
        }

        private fun fromLegacyCode(code: Char): MapLineColor? =
            when (code) {
                '0' -> MapLineColor(0, 0, 0)
                '1' -> MapLineColor(0, 0, 170)
                '2' -> MapLineColor(0, 170, 0)
                '3' -> MapLineColor(0, 170, 170)
                '4' -> MapLineColor(170, 0, 0)
                '5' -> MapLineColor(170, 0, 170)
                '6' -> MapLineColor(255, 170, 0)
                '7' -> MapLineColor(170, 170, 170)
                '8' -> MapLineColor(85, 85, 85)
                '9' -> MapLineColor(85, 85, 255)
                'a' -> MapLineColor(85, 255, 85)
                'b' -> MapLineColor(85, 255, 255)
                'c' -> MapLineColor(255, 85, 85)
                'd' -> MapLineColor(255, 85, 255)
                'e' -> MapLineColor(255, 255, 85)
                'f' -> WHITE
                else -> null
            }
    }
}
