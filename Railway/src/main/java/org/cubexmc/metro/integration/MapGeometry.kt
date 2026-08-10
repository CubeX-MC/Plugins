package org.cubexmc.metro.integration

import java.util.Optional
import org.cubexmc.metro.model.RoutePoint
import org.cubexmc.metro.model.Stop

class MapGeometry private constructor() {
    data class StopBounds(
        private val worldName: String,
        private val minX: Double,
        private val minY: Double,
        private val minZ: Double,
        private val maxX: Double,
        private val maxY: Double,
        private val maxZ: Double,
    ) {
        fun worldName(): String = worldName

        fun minX(): Double = minX

        fun minY(): Double = minY

        fun minZ(): Double = minZ

        fun maxX(): Double = maxX

        fun maxY(): Double = maxY

        fun maxZ(): Double = maxZ

        fun centerX(): Double = (minX + maxX) / 2.0

        fun centerY(): Double = (minY + maxY) / 2.0

        fun centerZ(): Double = (minZ + maxZ) / 2.0
    }

    companion object {
        private const val SAME_COORDINATE_EPSILON = 0.0001

        @JvmStatic
        fun orthogonalRoutePoints(routePoints: List<RoutePoint>?, worldName: String?): List<RoutePoint> {
            val result = ArrayList<RoutePoint>()
            if (routePoints == null || worldName == null || worldName.isBlank()) {
                return result
            }

            var previous: RoutePoint? = null
            for (current in routePoints) {
                if (current == null || worldName != current.worldName()) {
                    continue
                }
                if (previous == null) {
                    addIfDifferent(result, current)
                    previous = current
                    continue
                }

                val last = previous
                val dx = kotlin.math.abs(current.x() - last.x())
                val dz = kotlin.math.abs(current.z() - last.z())
                if (dx > SAME_COORDINATE_EPSILON && dz > SAME_COORDINATE_EPSILON) {
                    val elbow = if (dx >= dz) {
                        RoutePoint(worldName, current.x(), last.y(), last.z())
                    } else {
                        RoutePoint(worldName, last.x(), last.y(), current.z())
                    }
                    addIfDifferent(result, elbow)
                }
                addIfDifferent(result, current)
                previous = current
            }
            return result
        }

        @JvmStatic
        fun stopBounds(stop: Stop?): Optional<StopBounds> {
            if (stop == null) {
                return Optional.empty()
            }

            val corner1 = stop.corner1 ?: return Optional.empty()
            val corner2 = stop.corner2 ?: return Optional.empty()
            val world = corner1.world ?: return Optional.empty()
            if (corner2.world == null || world != corner2.world) {
                return Optional.empty()
            }

            val minX = kotlin.math.min(corner1.blockX, corner2.blockX).toDouble()
            val maxX = kotlin.math.max(corner1.blockX, corner2.blockX) + 1.0
            val minY = kotlin.math.min(corner1.blockY, corner2.blockY).toDouble()
            val maxY = kotlin.math.max(corner1.blockY, corner2.blockY) + 1.0
            val minZ = kotlin.math.min(corner1.blockZ, corner2.blockZ).toDouble()
            val maxZ = kotlin.math.max(corner1.blockZ, corner2.blockZ) + 1.0
            return Optional.of(StopBounds(world.name, minX, minY, minZ, maxX, maxY, maxZ))
        }

        private fun addIfDifferent(points: MutableList<RoutePoint>, point: RoutePoint) {
            if (points.isEmpty() || !samePosition(points[points.size - 1], point)) {
                points.add(point)
            }
        }

        private fun samePosition(left: RoutePoint, right: RoutePoint): Boolean =
            left.worldName() == right.worldName() &&
                kotlin.math.abs(left.x() - right.x()) <= SAME_COORDINATE_EPSILON &&
                kotlin.math.abs(left.y() - right.y()) <= SAME_COORDINATE_EPSILON &&
                kotlin.math.abs(left.z() - right.z()) <= SAME_COORDINATE_EPSILON
    }
}
