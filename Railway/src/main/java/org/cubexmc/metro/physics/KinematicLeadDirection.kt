package org.cubexmc.metro.physics

import org.bukkit.util.Vector
import org.cubexmc.metro.util.LocationUtil

internal object KinematicLeadDirection {

    @JvmStatic
    fun resolveFallbackDirection(lastLeadDirection: Vector?, travelDirection: Vector?): Vector {
        if (lastLeadDirection != null && lastLeadDirection.lengthSquared() > 1.0e-6) {
            return lastLeadDirection.clone().normalize()
        }
        if (travelDirection != null && travelDirection.lengthSquared() > 1.0e-6) {
            return travelDirection.clone().normalize()
        }
        return Vector(1, 0, 0)
    }

    @JvmStatic
    fun resolveRailDirection(
        railDirection: Vector?,
        fallbackDirection: Vector?,
        lastLeadDirection: Vector?,
        travelDirection: Vector?,
        currentSpeed: Double,
        railType: LocationUtil.RailType?,
    ): Vector {
        var resolved = railDirection?.clone()
        var fallback = fallbackDirection?.clone() ?: Vector(1, 0, 0)
        if (fallback.lengthSquared() < 1.0e-6) {
            fallback = Vector(1, 0, 0)
        }

        if (resolved == null || resolved.lengthSquared() < 1.0e-6) {
            return fallback.normalize()
        }

        resolved.normalize()
        if (currentSpeed < 0.2 && travelDirection != null && travelDirection.lengthSquared() > 1.0e-6 &&
            resolved.dot(travelDirection) < 0.0
        ) {
            resolved.multiply(-1.0)
        }

        val isSlope = railType == LocationUtil.RailType.ASCENDING || railType == LocationUtil.RailType.DESCENDING
        val blendFactor = if (isSlope) 0.85 else 0.4
        var previous = lastLeadDirection?.clone()
        if (previous != null && previous.lengthSquared() > 1.0e-6) {
            previous.normalize()
            var dot = resolved.dot(previous)
            if (dot < 0.0) {
                previous.multiply(-1.0)
                dot = resolved.dot(previous)
            }
            if (dot < if (isSlope) 0.99 else 0.707) {
                resolved = previous.multiply(1.0 - blendFactor).add(resolved.multiply(blendFactor)).normalize()
            }
        }

        return resolved
    }
}
