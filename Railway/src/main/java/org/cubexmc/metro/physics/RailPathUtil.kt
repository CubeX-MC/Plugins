package org.cubexmc.metro.physics

import java.util.EnumMap
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Rail
import org.bukkit.util.Vector
import kotlin.math.max
import kotlin.math.min

/**
 * Simple rail geometry helper that mirrors TrainCarts' rail paths for vanilla rail shapes.
 * Provides directional vectors and projection helpers that stay consistent with how TrainCarts walks rail segments.
 */
internal object RailPathUtil {

    private const val BASE_Y = 0.0625

    // === Optimization: Cache PathSpecs for all rail shapes to avoid allocation ===
    private val CACHED_SPECS = EnumMap<Rail.Shape, PathSpec>(Rail.Shape::class.java)

    init {
        // Straight rails
        register(
            Rail.Shape.NORTH_SOUTH,
            arrayOf(
                doubleArrayOf(0.5, BASE_Y, 0.0),
                doubleArrayOf(0.5, BASE_Y, 1.0),
            ),
        )
        register(
            Rail.Shape.EAST_WEST,
            arrayOf(
                doubleArrayOf(0.0, BASE_Y, 0.5),
                doubleArrayOf(1.0, BASE_Y, 0.5),
            ),
        )

        // Curved rails
        register(
            Rail.Shape.NORTH_EAST,
            arrayOf(
                doubleArrayOf(0.5, BASE_Y, 0.0),
                doubleArrayOf(0.75, BASE_Y, 0.25),
                doubleArrayOf(1.0, BASE_Y, 0.5),
            ),
        )
        register(
            Rail.Shape.NORTH_WEST,
            arrayOf(
                doubleArrayOf(0.5, BASE_Y, 0.0),
                doubleArrayOf(0.25, BASE_Y, 0.25),
                doubleArrayOf(0.0, BASE_Y, 0.5),
            ),
        )
        register(
            Rail.Shape.SOUTH_EAST,
            arrayOf(
                doubleArrayOf(0.5, BASE_Y, 1.0),
                doubleArrayOf(0.75, BASE_Y, 0.75),
                doubleArrayOf(1.0, BASE_Y, 0.5),
            ),
        )
        register(
            Rail.Shape.SOUTH_WEST,
            arrayOf(
                doubleArrayOf(0.5, BASE_Y, 1.0),
                doubleArrayOf(0.25, BASE_Y, 0.75),
                doubleArrayOf(0.0, BASE_Y, 0.5),
            ),
        )

        // Ascending rails
        register(
            Rail.Shape.ASCENDING_EAST,
            arrayOf(
                doubleArrayOf(0.0, BASE_Y, 0.5),
                doubleArrayOf(1.0, BASE_Y + 1.0, 0.5),
            ),
        )
        register(
            Rail.Shape.ASCENDING_WEST,
            arrayOf(
                doubleArrayOf(1.0, BASE_Y, 0.5),
                doubleArrayOf(0.0, BASE_Y + 1.0, 0.5),
            ),
        )
        register(
            Rail.Shape.ASCENDING_NORTH,
            arrayOf(
                doubleArrayOf(0.5, BASE_Y, 1.0),
                doubleArrayOf(0.5, BASE_Y + 1.0, 0.0),
            ),
        )
        register(
            Rail.Shape.ASCENDING_SOUTH,
            arrayOf(
                doubleArrayOf(0.5, BASE_Y, 0.0),
                doubleArrayOf(0.5, BASE_Y + 1.0, 1.0),
            ),
        )
    }

    private fun register(shape: Rail.Shape, points: Array<DoubleArray>) {
        CACHED_SPECS[shape] = PathSpec(points)
    }

    @JvmStatic
    fun computeDirection(location: Location?, fallback: Vector?): Vector {
        if (location == null) {
            return fallback?.clone() ?: Vector()
        }

        var block = location.block
        var spec = resolve(block)

        // Try block below if no rail found
        if (spec == null) {
            block = block.getRelative(0, -1, 0)
            spec = resolve(block)
        }

        if (spec == null) {
            return fallback?.clone() ?: Vector()
        }

        val direction = spec.bestSegmentDirection(location, block)
        if (direction == null || direction.lengthSquared() < 1.0e-8) {
            return fallback?.clone() ?: Vector()
        }
        if (fallback != null && fallback.lengthSquared() > 1.0e-8 && direction.dot(fallback) < 0.0) {
            direction.multiply(-1.0)
        }
        return direction.normalize()
    }

    @JvmStatic
    fun project(location: Location?): Location? {
        if (location == null) {
            return null
        }

        var block = location.block
        var spec = resolve(block)

        if (spec == null) {
            block = block.getRelative(0, -1, 0)
            spec = resolve(block)
        }

        if (spec == null) {
            return location
        }

        val projected = spec.project(location, block)
        return Location(location.world, projected.x, projected.y, projected.z)
    }

    /** Non-null companion for call sites whose Java source passed a known non-null location. */
    fun projectRequired(location: Location): Location =
        project(location) ?: throw NullPointerException("projected rail location")

    private fun resolve(block: Block?): PathSpec? {
        if (block == null) {
            return null
        }
        val rail = railData(block) ?: return null
        return CACHED_SPECS[rail.shape]
    }

    private fun railData(block: Block?): Rail? {
        if (block == null) {
            return null
        }
        val data = blockDataOrNull(block)
        return data as? Rail
    }

    /** Bukkit declares blockData non-null, but the Java implementation tolerated null from test doubles. */
    private fun blockDataOrNull(block: Block): BlockData? = block.blockData

    /** Stateless path specification calculated from relative points and a block offset. */
    class PathSpec internal constructor(private val relativePoints: Array<DoubleArray>) {

        fun bestSegmentDirection(location: Location, block: Block): Vector? {
            val locationX = location.x
            val locationY = location.y
            val locationZ = location.z

            val baseX = block.x.toDouble()
            val baseY = block.y.toDouble()
            val baseZ = block.z.toDouble()

            var bestDistanceSquared = Double.MAX_VALUE
            var bestDirectionX = 0.0
            var bestDirectionY = 0.0
            var bestDirectionZ = 0.0
            var found = false

            for (index in 0 until relativePoints.size - 1) {
                val first = relativePoints[index]
                val second = relativePoints[index + 1]

                val startX = baseX + first[0]
                val startY = baseY + first[1]
                val startZ = baseZ + first[2]

                val endX = baseX + second[0]
                val endY = baseY + second[1]
                val endZ = baseZ + second[2]

                val segmentX = endX - startX
                val segmentY = endY - startY
                val segmentZ = endZ - startZ

                val lengthSquared = segmentX * segmentX + segmentY * segmentY + segmentZ * segmentZ
                if (lengthSquared < 1.0e-8) {
                    continue
                }

                var interpolation = (
                    (locationX - startX) * segmentX +
                        (locationY - startY) * segmentY +
                        (locationZ - startZ) * segmentZ
                    ) / lengthSquared
                interpolation = max(0.0, min(1.0, interpolation))

                val projectedX = startX + segmentX * interpolation
                val projectedY = startY + segmentY * interpolation
                val projectedZ = startZ + segmentZ * interpolation

                val distanceSquared =
                    (projectedX - locationX) * (projectedX - locationX) +
                        (projectedY - locationY) * (projectedY - locationY) +
                        (projectedZ - locationZ) * (projectedZ - locationZ)

                if (distanceSquared < bestDistanceSquared) {
                    bestDistanceSquared = distanceSquared
                    bestDirectionX = segmentX
                    bestDirectionY = segmentY
                    bestDirectionZ = segmentZ
                    found = true
                }
            }

            return if (found) Vector(bestDirectionX, bestDirectionY, bestDirectionZ) else null
        }

        fun project(location: Location, block: Block): Vector {
            val locationX = location.x
            val locationY = location.y
            val locationZ = location.z

            val baseX = block.x.toDouble()
            val baseY = block.y.toDouble()
            val baseZ = block.z.toDouble()

            var bestDistanceSquared = Double.MAX_VALUE
            var bestPointX = 0.0
            var bestPointY = 0.0
            var bestPointZ = 0.0
            var found = false

            for (index in 0 until relativePoints.size - 1) {
                val first = relativePoints[index]
                val second = relativePoints[index + 1]

                val startX = baseX + first[0]
                val startY = baseY + first[1]
                val startZ = baseZ + first[2]

                val endX = baseX + second[0]
                val endY = baseY + second[1]
                val endZ = baseZ + second[2]

                val segmentX = endX - startX
                val segmentY = endY - startY
                val segmentZ = endZ - startZ

                val lengthSquared = segmentX * segmentX + segmentY * segmentY + segmentZ * segmentZ
                if (lengthSquared < 1.0e-8) {
                    continue
                }

                var interpolation = (
                    (locationX - startX) * segmentX +
                        (locationY - startY) * segmentY +
                        (locationZ - startZ) * segmentZ
                    ) / lengthSquared
                interpolation = max(0.0, min(1.0, interpolation))

                val projectedX = startX + segmentX * interpolation
                val projectedY = startY + segmentY * interpolation
                val projectedZ = startZ + segmentZ * interpolation

                val distanceSquared =
                    (projectedX - locationX) * (projectedX - locationX) +
                        (projectedY - locationY) * (projectedY - locationY) +
                        (projectedZ - locationZ) * (projectedZ - locationZ)

                if (distanceSquared < bestDistanceSquared) {
                    bestDistanceSquared = distanceSquared
                    bestPointX = projectedX
                    bestPointY = projectedY
                    bestPointZ = projectedZ
                    found = true
                }
            }

            if (!found) {
                // Return last point as fallback
                val last = relativePoints[relativePoints.size - 1]
                return Vector(baseX + last[0], baseY + last[1], baseZ + last[2])
            }
            return Vector(bestPointX, bestPointY, bestPointZ)
        }
    }
}
