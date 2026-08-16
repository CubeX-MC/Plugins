package org.cubexmc.metro.physics

import org.bukkit.Location
import org.bukkit.util.Vector

internal object KinematicRailMotionMath {

    @JvmStatic
    fun normalizeOr(primary: Vector?, fallback: Vector?): Vector {
        val candidate = primary?.clone()
        if (candidate != null && candidate.lengthSquared() > 1.0e-8) {
            return candidate.normalize()
        }
        val alternate = fallback?.clone()
        if (alternate != null && alternate.lengthSquared() > 1.0e-8) {
            return alternate.normalize()
        }
        return Vector(1, 0, 0)
    }

    @JvmStatic
    fun alignDirectionToRail(location: Location, preferredDirection: Vector?, fallbackDirection: Vector?): Vector {
        val motionDirection = normalizeOr(preferredDirection, fallbackDirection)
        val snapped = RailPathUtil.project(location.clone())
        val railDirection: Vector? = RailPathUtil.computeDirection(snapped, motionDirection)
        if (railDirection != null && railDirection.lengthSquared() > 1.0e-8) {
            return railDirection.normalize()
        }
        return motionDirection
    }

    @JvmStatic
    fun alignVelocityToRail(
        location: Location,
        velocity: Vector?,
        preferredDirection: Vector?,
        fallbackDirection: Vector?,
    ): Vector {
        if (velocity == null) {
            return Vector()
        }

        val base = velocity.clone()
        val alignedDirection = alignDirectionToRail(location, preferredDirection, fallbackDirection)
        return alignedDirection.multiply(base.length())
    }
}
