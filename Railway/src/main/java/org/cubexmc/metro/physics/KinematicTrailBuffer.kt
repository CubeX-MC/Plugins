package org.cubexmc.metro.physics

import java.util.ArrayDeque
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Minecart
import org.bukkit.util.Vector
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal class KinematicTrailBuffer {

    internal class TrailPoint(
        @JvmField val x: Double,
        @JvmField val y: Double,
        @JvmField val z: Double,
        @JvmField val vx: Double,
        @JvmField val vy: Double,
        @JvmField val vz: Double,
        @JvmField val cumulativeDistance: Double,
    )

    internal class TrailSample(
        @JvmField val location: Location,
        @JvmField val tangent: Vector,
        @JvmField val speed: Double,
    )

    private val trail = ArrayDeque<TrailPoint>()

    fun clear() {
        trail.clear()
    }

    fun isEmpty(): Boolean = trail.isEmpty()

    fun size(): Int = trail.size

    fun maintain(carCount: Int, spacing: Double) {
        if (trail.isEmpty()) {
            return
        }
        val head = trail.peekFirst() ?: return
        val maxDistance = (max(1, carCount) + 3) * max(0.1, spacing) + 12.0
        while (trail.size > 2) {
            val tail = trail.peekLast() ?: break
            val storedDistance = head.cumulativeDistance - tail.cumulativeDistance
            if (storedDistance <= maxDistance) {
                break
            }
            trail.removeLast()
        }
        while (trail.size > 800) {
            trail.removeLast()
        }
    }

    fun addPoint(x: Double, y: Double, z: Double, vx: Double, vy: Double, vz: Double) {
        var cumulativeDistance = 0.0

        if (trail.isNotEmpty()) {
            val last = trail.peekFirst()
            val dx = x - last.x
            val dy = y - last.y
            val dz = z - last.z
            val sectionDistance = sqrt(dx * dx + dy * dy + dz * dz)
            if (sectionDistance < 1.0e-4) {
                return
            }
            cumulativeDistance = last.cumulativeDistance + sectionDistance
        }

        trail.addFirst(TrailPoint(x, y, z, vx, vy, vz, cumulativeDistance))
        while (trail.size > 900) {
            trail.removeLast()
        }
    }

    fun seedFromConsist(cars: List<Minecart?>?, leadVelocity: Vector) {
        if (cars.isNullOrEmpty()) {
            return
        }

        val lead = cars[0]
        if (lead == null || lead.isDead) {
            return
        }

        var leadProjected = RailPathUtil.project(lead.location)
        if (leadProjected == null) {
            leadProjected = lead.location
        }

        val points = ArrayList<TrailPoint>()
        var cumulativeDistance = 0.0
        points.add(
            TrailPoint(
                leadProjected.x,
                leadProjected.y,
                leadProjected.z,
                leadVelocity.x,
                leadVelocity.y,
                leadVelocity.z,
                cumulativeDistance,
            ),
        )

        var previousLocation = leadProjected.clone()
        val fallbackVelocity = leadVelocity.clone()
        for (index in 1 until cars.size) {
            val car = cars[index]
            if (car == null || car.isDead) {
                continue
            }

            var projected = RailPathUtil.project(car.location)
            if (projected == null) {
                projected = car.location
            }

            val gap = projected.distance(previousLocation)
            if (gap > 1.0e-4) {
                cumulativeDistance -= gap
            }

            points.add(
                TrailPoint(
                    projected.x,
                    projected.y,
                    projected.z,
                    fallbackVelocity.x,
                    fallbackVelocity.y,
                    fallbackVelocity.z,
                    cumulativeDistance,
                ),
            )
            previousLocation = projected
        }

        trail.clear()
        for (index in points.size - 1 downTo 0) {
            trail.addFirst(points[index])
        }
    }

    fun sampleState(distanceBehind: Double, world: World?, fallbackDirection: Vector?): TrailSample? {
        val point = samplePointAt(distanceBehind)
        if (point == null || world == null) {
            return null
        }

        val location = Location(world, point.x, point.y, point.z)
        var tangent = computeTangent(distanceBehind)
        val speed = sqrt(point.vx * point.vx + point.vy * point.vy + point.vz * point.vz)

        if ((tangent == null || tangent.lengthSquared() < 1.0e-8) && speed > 1.0e-6) {
            tangent = Vector(point.vx, point.vy, point.vz)
        }
        if ((tangent == null || tangent.lengthSquared() < 1.0e-8) && fallbackDirection != null) {
            tangent = fallbackDirection.clone()
        }
        if (tangent == null || tangent.lengthSquared() < 1.0e-8) {
            tangent = Vector(1, 0, 0)
        }

        return TrailSample(location, tangent, speed)
    }

    fun samplePointAt(distanceBehind: Double): TrailPoint? {
        if (trail.isEmpty()) {
            return null
        }

        val head = trail.peekFirst()
        val targetDistance = head.cumulativeDistance - distanceBehind
        if (targetDistance < 0) {
            return trail.peekLast()
        }

        var previous = head
        for (point in trail) {
            if (point.cumulativeDistance <= targetDistance) {
                val segmentDistance = previous.cumulativeDistance - point.cumulativeDistance
                if (segmentDistance < 1.0e-6) {
                    return previous
                }

                var interpolation = (targetDistance - point.cumulativeDistance) / segmentDistance
                interpolation = max(0.0, min(1.0, interpolation))
                return TrailPoint(
                    point.x + interpolation * (previous.x - point.x),
                    point.y + interpolation * (previous.y - point.y),
                    point.z + interpolation * (previous.z - point.z),
                    point.vx + interpolation * (previous.vx - point.vx),
                    point.vy + interpolation * (previous.vy - point.vy),
                    point.vz + interpolation * (previous.vz - point.vz),
                    targetDistance,
                )
            }
            previous = point
        }

        return trail.peekLast()
    }

    fun computeTangent(distanceBehind: Double): Vector? {
        val delta = max(0.05, min(0.75, distanceBehind * 0.5 + 0.1))
        val aheadDistance = max(0.0, distanceBehind - delta)
        val behindDistance = distanceBehind + delta
        val ahead = samplePointAt(aheadDistance)
        val behind = samplePointAt(behindDistance)
        if (ahead == null || behind == null) {
            val center = samplePointAt(distanceBehind)
            return if (center != null) Vector(center.vx, center.vy, center.vz) else null
        }

        var tangent = Vector(ahead.x - behind.x, ahead.y - behind.y, ahead.z - behind.z)
        if (tangent.lengthSquared() < 1.0e-8) {
            val center = samplePointAt(distanceBehind)
            if (center != null) {
                tangent = Vector(center.vx, center.vy, center.vz)
            }
        }
        return tangent
    }
}
