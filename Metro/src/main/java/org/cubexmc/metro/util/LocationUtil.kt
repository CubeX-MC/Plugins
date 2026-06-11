package org.cubexmc.metro.util

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.util.Vector
import java.util.EnumSet

/**
 * 位置工具类，用于位置数据的序列化和反序列化
 */
object LocationUtil {
    private val RAIL_MATERIALS: Set<Material> = EnumSet.of(
        Material.RAIL,
        Material.POWERED_RAIL,
        Material.DETECTOR_RAIL,
        Material.ACTIVATOR_RAIL,
    )

    @JvmStatic
    fun locationToString(location: Location?): String? {
        val world = location?.world ?: return null
        return String.format("%s,%d,%d,%d", world.name, location.blockX, location.blockY, location.blockZ)
    }

    @JvmStatic
    fun isRail(location: Location?): Boolean {
        if (location == null) {
            return false
        }
        return RAIL_MATERIALS.contains(location.block.type)
    }

    @JvmStatic
    fun isOnRail(location: Location?): Boolean {
        if (location == null) {
            return false
        }
        if (isRail(location)) {
            return true
        }
        val belowLocation = location.clone().subtract(0.0, 1.0, 0.0)
        return isRail(belowLocation)
    }

    @JvmStatic
    fun getDirectionVector(from: Location, to: Location): Vector = to.toVector().subtract(from.toVector()).normalize()
}
