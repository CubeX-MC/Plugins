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
    private val logger = Logger.getGlobal()
    private val maxSnapDistance = 3.0
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
            logger.log(
                Level.INFO,
                "[RouteNormalizer] {0}/{1} points could not be snapped to a rail block",
                arrayOf<Any>(missed, points.size),
            )
        }
        return result
    }

    private fun snapPoint(point: RoutePoint?): RoutePoint? {
        if (point == null) {
            return null
        }
        val world = try {
            Bukkit.getWorld(point.worldName())
        } catch (_: Exception) {
            return point
        } ?: return point

        return try {
            snapPointInWorld(point, world)
        } catch (exception: Exception) {
            logger.log(Level.WARNING, "[RouteNormalizer] Failed to snap point", exception)
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
        var bestDistance = maxSnapDistance * maxSnapDistance

        for (dy in -1..1) {
            for (dx in -2..2) {
                for (dz in -2..2) {
                    val x = baseX + dx
                    val y = baseY + dy
                    val z = baseZ + dz
                    val block = world.getBlockAt(x, y, z)
                    if (!isRail(block)) {
                        continue
                    }
                    val distance = distanceSquaredToBlockCenter(point, x, y, z)
                    if (distance < bestDistance) {
                        bestDistance = distance
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
            if (type == rail) {
                return true
            }
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
        val epsilonSquared = epsilon * epsilon
        for (index in 1 until points.size - 1) {
            val previous = result[result.size - 1]
            val current = points[index]
            val next = points[index + 1]
            if (!isRedundantCollinear(previous, current, next, epsilonSquared)) {
                result.add(current)
            }
        }
        result.add(points[points.size - 1])
        return result
    }

    private fun isRedundantCollinear(
        first: RoutePoint,
        middle: RoutePoint,
        last: RoutePoint,
        epsilonSquared: Double,
    ): Boolean {
        if (first.worldName() != middle.worldName() || middle.worldName() != last.worldName()) {
            return false
        }
        val abX = middle.x() - first.x()
        val abY = middle.y() - first.y()
        val abZ = middle.z() - first.z()
        val acX = last.x() - first.x()
        val acY = last.y() - first.y()
        val acZ = last.z() - first.z()
        val abLengthSquared = abX * abX + abY * abY + abZ * abZ
        if (abLengthSquared < 1e-12) {
            return true
        }
        val acLengthSquared = acX * acX + acY * acY + acZ * acZ
        if (acLengthSquared < 1e-12) {
            return false
        }
        val dot = abX * acX + abY * acY + abZ * acZ
        if (dot < 0 || dot * dot > abLengthSquared * acLengthSquared) {
            return false
        }
        val crossX = abY * acZ - abZ * acY
        val crossY = abZ * acX - abX * acZ
        val crossZ = abX * acY - abY * acX
        val distanceSquared =
            (crossX * crossX + crossY * crossY + crossZ * crossZ) / acLengthSquared
        return distanceSquared <= epsilonSquared
    }
}
