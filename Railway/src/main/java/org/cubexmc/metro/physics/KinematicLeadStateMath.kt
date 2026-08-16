package org.cubexmc.metro.physics

import org.bukkit.Location
import org.bukkit.util.Vector
import org.cubexmc.metro.util.LocationUtil

internal object KinematicLeadStateMath {

    internal class LeadState(
        @JvmField val x: Double,
        @JvmField val y: Double,
        @JvmField val z: Double,
        @JvmField val vx: Double,
        @JvmField val vy: Double,
        @JvmField val vz: Double,
        @JvmField val location: Location,
    )

    @JvmStatic
    fun advanceAndRecover(currentLocation: Location, correctedVelocity: Vector, timeFraction: Double): LeadState {
        val step = correctedVelocity.clone().multiply(timeFraction)

        var nextX = currentLocation.x + step.x
        var nextY = currentLocation.y + step.y
        var nextZ = currentLocation.z + step.z

        var nextLocation = Location(currentLocation.world, nextX, nextY, nextZ)
        if (!LocationUtil.isRail(nextLocation)) {
            val snapped = LocationUtil.snapToRail(nextLocation, currentLocation.world)
            if (snapped != null && snapped.distanceSquared(nextLocation) < 4.0) {
                nextLocation = snapped
                nextX = snapped.x
                nextY = snapped.y
                nextZ = snapped.z
            }
        }

        nextLocation = RailPathUtil.project(nextLocation)
        nextX = nextLocation.x
        nextY = nextLocation.y
        nextZ = nextLocation.z

        return LeadState(
            nextX,
            nextY,
            nextZ,
            correctedVelocity.x,
            correctedVelocity.y,
            correctedVelocity.z,
            nextLocation,
        )
    }
}
