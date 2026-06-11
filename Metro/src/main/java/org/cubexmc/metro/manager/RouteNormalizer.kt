package org.cubexmc.metro.manager

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.cubexmc.metro.model.RoutePoint
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.floor

/**
 * Normalizes raw minecart route recordings by snapping float-point positions
 * to nearest rail block centers and retaining direction / world / Y change points.
 */
class RouteNormalizer {
    private val railMaterials = arrayOf(
        Material.RAIL,
        Material.POWERED_RAIL,
        Material.DETECTOR_RAIL,
        Material.ACTIVATOR_RAIL,
    )

    fun normalize(points: List<RoutePoint>?, simplifyEpsilon: Double): List<RoutePoint> {
        if (points.isNullOrEmpty()) {
            return points ?: emptyList()
        }

        var snapped = snapToRailCenters(points)
        if (simplifyEpsilon > 0 && snapped.size >= 3) {
            snapped = simplifyCollinearPoints(snapped, simplifyEpsilon)
        }
        return snapped
    }

    private fun snapToRailCenters(points: List<RoutePoint>): List<RoutePoint> {
        val result = ArrayList<RoutePoint>(points.size)
        var missed = 0

        for (point in points) {
            val snapped = snapPoint(point)
            if (snapped != null) {
                result.add(snapped)
            } else {
                missed++
            }
        }

        if (missed > 0) {
            LOGGER.log(
                Level.INFO,
                "[RouteNormalizer] {0}/{1} points could not be snapped to a rail block",
                arrayOf<Any>(missed, points.size),
            )
        }
        return result
    }

    private fun snapPoint(point: RoutePoint?): RoutePoint? {
        if (point == null) return null
        val world = try {
            Bukkit.getWorld(point.worldName())
        } catch (_: Exception) {
            return point
        } ?: return point

        return try {
            snapPointInWorld(point, world)
        } catch (e: Exception) {
            LOGGER.log(Level.WARNING, "[RouteNormalizer] Failed to snap point", e)
            point
        }
    }

    private fun snapPointInWorld(point: RoutePoint, world: World): RoutePoint {
        val baseX = floor(point.x()).toInt()
        val baseY = floor(point.y()).toInt()
        val baseZ = floor(point.z()).toInt()

        var bestX = 0
        var bestY = 0
        var bestZ = 0
        var found = false
        var bestDistance = MAX_SNAP_DISTANCE * MAX_SNAP_DISTANCE

        for (dy in -1..1) {
            for (dx in -2..2) {
                for (dz in -2..2) {
                    val x = baseX + dx
                    val y = baseY + dy
                    val z = baseZ + dz
                    val block = world.getBlockAt(x, y, z)
                    if (!isRail(block)) continue
                    val dist = distanceSquaredToBlockCenter(point, x, y, z)
                    if (dist < bestDistance) {
                        bestDistance = dist
                        found = true
                        bestX = x
                        bestY = y
                        bestZ = z
                    }
                }
            }
        }

        return if (found) {
            RoutePoint(point.worldName(), bestX + 0.5, bestY + 0.5, bestZ + 0.5)
        } else {
            point
        }
    }

    private fun isRail(block: Block): Boolean {
        val type = block.type
        for (rail in railMaterials) {
            if (type == rail) return true
        }
        return false
    }

    private fun distanceSquaredToBlockCenter(point: RoutePoint, x: Int, y: Int, z: Int): Double {
        val dx = point.x() - (x + 0.5)
        val dy = point.y() - (y + 0.5)
        val dz = point.z() - (z + 0.5)
        return dx * dx + dy * dy + dz * dz
    }

    private fun simplifyCollinearPoints(points: List<RoutePoint>, epsilon: Double): List<RoutePoint> {
        val result = ArrayList<RoutePoint>()
        result.add(points[0])
        val epsSq = epsilon * epsilon

        for (i in 1 until points.size - 1) {
            val prev = result[result.size - 1]
            val curr = points[i]
            val next = points[i + 1]

            if (!isRedundantCollinear(prev, curr, next, epsSq)) {
                result.add(curr)
            }
        }
        result.add(points[points.size - 1])
        return result
    }

    private fun isRedundantCollinear(a: RoutePoint, b: RoutePoint, c: RoutePoint, epsSq: Double): Boolean {
        if (a.worldName() != b.worldName() || b.worldName() != c.worldName()) {
            return false
        }

        val abx = b.x() - a.x()
        val aby = b.y() - a.y()
        val abz = b.z() - a.z()
        val acx = c.x() - a.x()
        val acy = c.y() - a.y()
        val acz = c.z() - a.z()

        val abLenSq = abx * abx + aby * aby + abz * abz
        if (abLenSq < 1e-12) return true

        val acLenSq = acx * acx + acy * acy + acz * acz
        if (acLenSq < 1e-12) return false

        val dot = abx * acx + aby * acy + abz * acz
        if (dot < 0) return false
        if (dot * dot > abLenSq * acLenSq) return false

        val crossX = aby * acz - abz * acy
        val crossY = abz * acx - abx * acz
        val crossZ = abx * acy - aby * acx
        val distSq = (crossX * crossX + crossY * crossY + crossZ * crossZ) / acLenSq

        return distSq <= epsSq
    }

    companion object {
        private val LOGGER: Logger = Logger.getGlobal()
        private const val MAX_SNAP_DISTANCE = 3.0
    }
}
