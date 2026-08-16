package org.cubexmc.metro.physics

import org.bukkit.Location
import org.bukkit.util.Vector
import org.cubexmc.metro.util.LocationUtil
import kotlin.math.max

internal object KinematicSpacingMath {

    private const val CART_DISTANCE_FORCER = 0.1

    @JvmStatic
    fun applyTerrainBoost(
        poweredAscendingRailPowered: Boolean,
        railType: LocationUtil.RailType?,
        currentSpeed: Double,
        baseSpeed: Double,
    ): Double {
        if (poweredAscendingRailPowered) {
            val minSpeed = max(0.4, baseSpeed * 0.8)
            return max(currentSpeed, minSpeed)
        }

        if (railType == LocationUtil.RailType.CURVE) {
            val criticalMin = max(0.15, baseSpeed * 0.5)
            return max(currentSpeed, criticalMin)
        }

        return currentSpeed
    }

    @JvmStatic
    fun calculateLeadCorrection(
        leadLocation: Location?,
        followerLocation: Location?,
        spacing: Double,
        fallbackDirection: Vector?,
    ): Vector {
        if (leadLocation == null || followerLocation == null) {
            return Vector(0, 0, 0)
        }

        var direction = followerLocation.toVector().subtract(leadLocation.toVector())
        var actualGap = direction.length()

        if (actualGap < 1.0e-6) {
            var fallback = fallbackDirection?.clone() ?: Vector(1, 0, 0)
            if (fallback.lengthSquared() < 1.0e-8) {
                fallback = Vector(1, 0, 0)
            }
            direction = fallback.normalize()
            actualGap = spacing
        } else {
            direction.normalize()
        }

        val gapError = actualGap - spacing
        val correction = direction.multiply(gapError)

        val maxCorrection = spacing * 0.5
        val correctionLength = correction.length()
        if (correctionLength > maxCorrection) {
            correction.multiply(maxCorrection / correctionLength)
        }

        return correction
    }

    @JvmStatic
    fun applySpacingUpdate(baseVelocity: Vector?, correction: Vector?, maxSpeed: Double): Vector {
        val result = baseVelocity?.clone() ?: Vector()
        if (correction == null || correction.lengthSquared() < 1.0e-8) {
            return result
        }

        val motionLength = result.length()
        val effectiveSpeed = max(motionLength, 0.2)
        val safeMaxSpeed = max(0.05, maxSpeed)
        val factor = effectiveSpeed / safeMaxSpeed

        result.x = result.x + factor * correction.x * CART_DISTANCE_FORCER
        result.z = result.z + factor * correction.z * CART_DISTANCE_FORCER
        return result
    }

    @JvmStatic
    fun clampVelocity(velocity: Vector?, limit: Double): Vector {
        val result = velocity?.clone() ?: Vector()
        val safeLimit = max(0.05, limit)
        val length = result.length()
        if (length > safeLimit) {
            result.multiply(safeLimit / length)
        }
        return result
    }
}
