package org.cubexmc.metro.physics

import org.bukkit.Location
import org.bukkit.util.Vector
import org.cubexmc.metro.util.LocationUtil

internal object KinematicLeadBootstrap {

    @JvmRecord
    data class BootstrapState(
        val location: Location,
        val velocity: Vector,
        val facingDirection: Vector,
    )

    @JvmStatic
    fun initialize(
        location: Location?,
        travelDirection: Vector?,
        currentVelocity: Vector?,
        speed: Double,
    ): BootstrapState {
        var resolvedLocation = location?.clone()
        val world = resolvedLocation?.world
        if (resolvedLocation != null && world != null) {
            val snapped = LocationUtil.snapToRail(resolvedLocation, world)
            if (snapped != null) {
                resolvedLocation = snapped
            }
        }

        val resolvedVelocity: Vector
        val resolvedFacing: Vector
        if (travelDirection != null && travelDirection.lengthSquared() > 1.0e-6) {
            resolvedFacing = travelDirection.clone().normalize()
            resolvedVelocity = resolvedFacing.clone().multiply(speed)
        } else if (currentVelocity != null && currentVelocity.lengthSquared() > 1.0e-6) {
            resolvedVelocity = currentVelocity.clone()
            resolvedFacing = currentVelocity.clone().normalize()
        } else {
            resolvedVelocity = Vector()
            resolvedFacing = Vector(1, 0, 0)
        }

        if (resolvedLocation == null) {
            resolvedLocation = Location(null, 0.0, 0.0, 0.0)
        }
        return BootstrapState(resolvedLocation, resolvedVelocity, resolvedFacing)
    }

    @JvmStatic
    fun resolveSeedVelocity(authoritativeVelocity: Vector?, lastLeadDirection: Vector?, speed: Double): Vector {
        if (authoritativeVelocity != null && authoritativeVelocity.lengthSquared() > 1.0e-6) {
            return authoritativeVelocity.clone()
        }
        val fallbackDirection = KinematicRailMotionMath.normalizeOr(lastLeadDirection, Vector(1, 0, 0))
        return fallbackDirection.multiply(speed)
    }
}
