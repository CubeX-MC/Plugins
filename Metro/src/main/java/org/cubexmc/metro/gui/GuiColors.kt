package org.cubexmc.metro.gui

import java.util.Locale
import org.bukkit.Material

object GuiColors {
    @JvmStatic
    fun getWoolByColor(colorCode: String?): Material {
        if (colorCode == null) {
            return Material.WHITE_WOOL
        }

        val code = colorCode.replace("&", "").lowercase(Locale.getDefault())

        return when (code) {
            "0" -> Material.BLACK_WOOL
            "1" -> Material.BLUE_WOOL
            "2" -> Material.GREEN_WOOL
            "3" -> Material.CYAN_WOOL
            "4" -> Material.RED_WOOL
            "5" -> Material.PURPLE_WOOL
            "6" -> Material.ORANGE_WOOL
            "7" -> Material.LIGHT_GRAY_WOOL
            "8" -> Material.GRAY_WOOL
            "9" -> Material.LIGHT_BLUE_WOOL
            "a" -> Material.LIME_WOOL
            "b" -> Material.LIGHT_BLUE_WOOL
            "c" -> Material.RED_WOOL
            "d" -> Material.PINK_WOOL
            "e" -> Material.YELLOW_WOOL
            "f" -> Material.WHITE_WOOL
            else -> Material.WHITE_WOOL
        }
    }
}
