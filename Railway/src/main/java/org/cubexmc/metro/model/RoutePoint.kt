package org.cubexmc.metro.model

import org.bukkit.Location

data class RoutePoint(
    private val worldName: String,
    private val x: Double,
    private val y: Double,
    private val z: Double,
) {
    fun worldName(): String = worldName

    fun x(): Double = x

    fun y(): Double = y

    fun z(): Double = z

    fun toConfigString(): String = "$worldName,$x,$y,$z"

    fun distanceSquared(other: RoutePoint?): Double {
        if (other == null || worldName != other.worldName()) {
            return Double.MAX_VALUE
        }
        val dx = x - other.x()
        val dy = y - other.y()
        val dz = z - other.z()
        return dx * dx + dy * dy + dz * dz
    }

    companion object {
        @JvmStatic
        fun fromLocation(location: Location?): RoutePoint? {
            val world = location?.world ?: return null
            return RoutePoint(world.name, location.x, location.y, location.z)
        }

        @JvmStatic
        fun fromConfigString(value: String?): RoutePoint? {
            if (value.isNullOrBlank()) {
                return null
            }
            val parts = value.split(",")
            if (parts.size != 4) {
                return null
            }
            return try {
                RoutePoint(
                    parts[0],
                    parts[1].toDouble(),
                    parts[2].toDouble(),
                    parts[3].toDouble(),
                )
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}
