package org.cubexmc.metro.util

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.Powerable
import org.bukkit.block.data.Rail
import org.bukkit.util.Vector
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

object LocationUtil {

    enum class RailType {
        STRAIGHT,
        CURVE,
        ASCENDING,
        DESCENDING,
        UNKNOWN,
    }

    private const val EPSILON = 1e-6
    private const val CURVE_CENTER_EPSILON = 1e-3
    private const val BLOCK_CENTER_OFFSET = 0.5

    @Suppress("unused")
    private val RAIL_MATERIALS: Set<Material> = EnumSet.of(
        Material.RAIL,
        Material.POWERED_RAIL,
        Material.DETECTOR_RAIL,
        Material.ACTIVATOR_RAIL,
    )

    @JvmStatic
    fun locationToString(location: Location?): String? {
        val world = location?.world ?: return null
        return String.format(
            "%s,%d,%d,%d",
            world.name,
            location.blockX,
            location.blockY,
            location.blockZ,
        )
    }

    @JvmStatic
    fun locationFromString(world: String?, locationString: String?): Location? {
        if (locationString == null) return null
        val parts = locationString.split(",")
        if (parts.size != 4) return null
        return try {
            val x = parts[1].toDouble()
            val y = parts[2].toDouble()
            val z = parts[3].toDouble()
            Location(Bukkit.getWorld(world ?: parts[0]), x, y, z)
        } catch (_: NumberFormatException) {
            null
        }
    }

    @JvmStatic
    fun isSameLocation(loc1: Location?, loc2: Location?): Boolean {
        if (loc1 == null || loc2 == null) return false
        val world = loc1.world ?: return false
        return world == loc2.world &&
            loc1.blockX == loc2.blockX &&
            loc1.blockY == loc2.blockY &&
            loc1.blockZ == loc2.blockZ
    }

    @JvmStatic
    fun getDirectionVector(from: Location, to: Location): Vector =
        to.toVector().subtract(from.toVector()).normalize()

    @JvmStatic
    fun isRail(location: Location?): Boolean {
        if (location == null) return false
        val block = location.block
        if (isRailBlock(block.type)) return true
        // Java 版本对 getRelative 的结果做过 null 判断（测试里的 mock Block 会返回 null），保持同样的宽容
        val below = relativeBlockOrNull(block, 0, -1, 0) ?: return false
        return isRailBlock(below.type)
    }

    /** Bukkit 标注 getRelative 为非空，这里刻意放宽成可空以保留 Java 版本的防御性判断。 */
    private fun relativeBlockOrNull(block: Block, x: Int, y: Int, z: Int): Block? = block.getRelative(x, y, z)

    @JvmStatic
    fun isOnRail(location: Location?): Boolean = isRail(location)

    private fun isRailBlock(material: Material?): Boolean = material != null && material.name.contains("RAIL")

    @JvmStatic
    fun isPoweredAscendingRailPowered(location: Location?): Boolean {
        if (location == null) return false
        val rail = getRailData(location.block) ?: getRailData(location.block.getRelative(0, -1, 0)) ?: return false
        val shape = rail.shape
        val ascending = shape == Rail.Shape.ASCENDING_EAST ||
            shape == Rail.Shape.ASCENDING_WEST ||
            shape == Rail.Shape.ASCENDING_NORTH ||
            shape == Rail.Shape.ASCENDING_SOUTH
        if (!ascending) return false
        return rail is Powerable && (rail as Powerable).isPowered
    }

    private fun getRailData(block: Block?): Rail? {
        if (block == null) return null
        val data = block.blockData
        return data as? Rail
    }

    @JvmStatic
    fun isCurveRail(location: Location?): Boolean {
        val rail = getRailData(location?.block)
            ?: getRailData(location?.block?.getRelative(0, -1, 0))
            ?: return false
        return when (rail.shape) {
            Rail.Shape.NORTH_EAST, Rail.Shape.NORTH_WEST, Rail.Shape.SOUTH_EAST, Rail.Shape.SOUTH_WEST -> true
            else -> false
        }
    }

    private fun computeRailCenter(block: Block, rail: Rail): Location {
        val center = Location(
            block.world,
            block.x + BLOCK_CENTER_OFFSET,
            block.y.toDouble(),
            block.z + BLOCK_CENTER_OFFSET,
        )
        when (rail.shape) {
            Rail.Shape.ASCENDING_EAST,
            Rail.Shape.ASCENDING_WEST,
            Rail.Shape.ASCENDING_NORTH,
            Rail.Shape.ASCENDING_SOUTH,
            -> center.add(0.0, BLOCK_CENTER_OFFSET, 0.0)

            else -> Unit
        }
        return center
    }

    private fun centerRail(candidate: Location?): Location? {
        if (candidate == null) return null
        val block = candidate.block
        val rail = getRailData(block)
        if (rail != null) return computeRailCenter(block, rail)
        val below = relativeBlockOrNull(block, 0, -1, 0)
        val belowRail = getRailData(below)
        if (below != null && belowRail != null) return computeRailCenter(below, belowRail)
        return center(candidate)
    }

    @JvmStatic
    fun getRailType(location: Location?): RailType {
        if (location == null) return RailType.UNKNOWN
        val block = location.block
        var data = block.blockData
        if (data !is Rail) {
            val below = block.getRelative(0, -1, 0)
            val belowData = below.blockData
            if (belowData is Rail) {
                data = belowData
            } else {
                return RailType.UNKNOWN
            }
        }
        return when ((data as Rail).shape) {
            Rail.Shape.NORTH_SOUTH, Rail.Shape.EAST_WEST -> RailType.STRAIGHT
            Rail.Shape.NORTH_EAST,
            Rail.Shape.NORTH_WEST,
            Rail.Shape.SOUTH_EAST,
            Rail.Shape.SOUTH_WEST,
            -> RailType.CURVE

            Rail.Shape.ASCENDING_EAST,
            Rail.Shape.ASCENDING_WEST,
            Rail.Shape.ASCENDING_NORTH,
            Rail.Shape.ASCENDING_SOUTH,
            -> RailType.ASCENDING

            else -> RailType.UNKNOWN
        }
    }

    @JvmStatic
    @Suppress("MagicNumber")
    fun getSafeSpeedForRail(type: RailType, baseSpeed: Double, safeMode: Boolean): Double {
        if (!safeMode) return baseSpeed
        return when (type) {
            RailType.STRAIGHT -> baseSpeed
            RailType.CURVE -> min(baseSpeed * 0.65, 0.25)
            RailType.ASCENDING -> min(baseSpeed * 0.87, 0.35)
            RailType.DESCENDING -> min(baseSpeed * 0.95, 0.42)
            else -> min(baseSpeed * 0.80, 0.32)
        }
    }

    @JvmStatic
    fun vectorFromYaw(yaw: Float): Vector {
        val radians = Math.toRadians(yaw.toDouble())
        val x = -sin(radians)
        val z = cos(radians)
        val vector = Vector(x, 0.0, z)
        return if (vector.lengthSquared() == 0.0) Vector(0, 0, 0) else vector.normalize()
    }

    @JvmStatic
    fun center(location: Location?): Location? {
        if (location == null) return null
        val x = location.x
        val z = location.z
        if (abs(x - floor(x)) < EPSILON && abs(z - floor(z)) < EPSILON) {
            return location.clone().add(BLOCK_CENTER_OFFSET, 0.0, BLOCK_CENTER_OFFSET)
        }
        return location.clone()
    }

    @JvmStatic
    fun snapToRail(location: Location?, world: World?): Location? {
        if (location == null || world == null) return location
        var best: Location? = null
        var bestDist = Double.MAX_VALUE
        val bx = location.blockX
        val by = location.blockY
        val bz = location.blockZ
        for (y in -1..1) {
            for (x in -1..1) {
                for (z in -1..1) {
                    val candidate = Location(world, (bx + x).toDouble(), (by + y).toDouble(), (bz + z).toDouble())
                    if (!isRail(candidate)) continue
                    val center = centerRail(candidate) ?: continue
                    val distance = center.distanceSquared(location)
                    if (distance < bestDist) {
                        bestDist = distance
                        best = center
                    }
                }
            }
        }
        return best ?: location
    }

    @JvmStatic
    fun findNearestRail(location: Location?, radius: Double): Location? {
        val world = location?.world ?: return null
        var best: Location? = null
        var bestDistSq = radius * radius
        val r = ceil(radius).toInt()
        val bx = location.blockX
        val by = location.blockY
        val bz = location.blockZ
        for (dx in -r..r) {
            for (dy in -r..r) {
                for (dz in -r..r) {
                    val candidate =
                        Location(world, (bx + dx).toDouble(), (by + dy).toDouble(), (bz + dz).toDouble())
                    if (!isRail(candidate)) continue
                    val center = centerRail(candidate) ?: continue
                    val distance = center.distanceSquared(location)
                    if (distance < bestDistSq) {
                        bestDistSq = distance
                        best = center
                    }
                }
            }
        }
        return best
    }

    @JvmStatic
    fun railDirection(location: Location?, fallback: Vector?): Vector? {
        if (location == null) return fallback
        val block = location.block
        val data = block.blockData
        val rail = data as? Rail ?: block.getRelative(0, -1, 0).blockData as? Rail
        if (rail != null) {
            val dir = vectorFromRailShape(rail.shape, location, fallback)
            if (dir != null) {
                val shape = rail.shape
                if (shape != Rail.Shape.NORTH_EAST && shape != Rail.Shape.NORTH_WEST &&
                    shape != Rail.Shape.SOUTH_EAST && shape != Rail.Shape.SOUTH_WEST
                ) {
                    if (fallback != null && dir.dot(fallback) < 0) dir.multiply(-1)
                }
                return dir
            }
        }
        return fallback
    }

    private fun vectorFromRailShape(shape: Rail.Shape?, location: Location, fallback: Vector?): Vector? {
        if (shape == null) return null
        return when (shape) {
            Rail.Shape.NORTH_SOUTH -> Vector(0, 0, 1)
            Rail.Shape.EAST_WEST -> Vector(1, 0, 0)
            Rail.Shape.ASCENDING_EAST -> Vector(1, 1, 0)
            Rail.Shape.ASCENDING_WEST -> Vector(-1, 1, 0)
            Rail.Shape.ASCENDING_NORTH -> Vector(0, 1, -1)
            Rail.Shape.ASCENDING_SOUTH -> Vector(0, 1, 1)
            Rail.Shape.SOUTH_EAST ->
                computeCurveDirection(location, fallback, Vector(0, 0, 1), Vector(1, 0, 0))

            Rail.Shape.SOUTH_WEST ->
                computeCurveDirection(location, fallback, Vector(0, 0, 1), Vector(-1, 0, 0))

            Rail.Shape.NORTH_WEST ->
                computeCurveDirection(location, fallback, Vector(0, 0, -1), Vector(-1, 0, 0))

            Rail.Shape.NORTH_EAST ->
                computeCurveDirection(location, fallback, Vector(0, 0, -1), Vector(1, 0, 0))
        }
    }

    private fun computeCurveDirection(
        location: Location,
        fallback: Vector?,
        dir1: Vector,
        dir2: Vector,
    ): Vector {
        val dx = location.x - floor(location.x) - BLOCK_CENTER_OFFSET
        val dz = location.z - floor(location.z) - BLOCK_CENTER_OFFSET
        val radial: Vector
        if (abs(dx) < CURVE_CENTER_EPSILON && abs(dz) < CURVE_CENTER_EPSILON) {
            var fb = fallback?.clone() ?: Vector(1, 0, 0)
            if (fb.lengthSquared() < EPSILON) fb = Vector(1, 0, 0)
            fb.y = 0.0
            fb.normalize()
            radial = Vector(-fb.x, 0.0, -fb.z)
        } else {
            radial = Vector(dx, 0.0, dz)
        }
        val cw = Vector(radial.z, 0.0, -radial.x)
        val ccw = Vector(-radial.z, 0.0, radial.x)
        if (fallback == null || fallback.lengthSquared() < EPSILON) {
            val avg = dir1.clone().add(dir2).normalize()
            return (if (cw.dot(avg) >= ccw.dot(avg)) cw else ccw).normalize()
        }
        return (if (cw.dot(fallback) >= ccw.dot(fallback)) cw else ccw).normalize()
    }
}
