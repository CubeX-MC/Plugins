package org.cubexmc.metro.physics

import org.bukkit.Location
import org.bukkit.util.Vector
import org.cubexmc.metro.util.LocationUtil

internal object KinematicLeadMotionPlanner {

    internal class LeadMotionCommand(
        @JvmField val facingDirection: Vector,
        @JvmField val correctedVelocity: Vector,
        @JvmField val leadState: KinematicLeadStateMath.LeadState,
    )

    @JvmStatic
    fun plan(
        currentLocation: Location,
        lastLeadDirection: Vector?,
        travelDirection: Vector?,
        liveLeadSpeed: Double,
        baseSpeed: Double,
        safeMode: Boolean,
        lookaheadBlocks: Int,
        maxSpeed: Double,
        spacingCorrection: Vector?,
        timeFraction: Double,
    ): LeadMotionCommand {
        val fallbackDirection = KinematicLeadDirection.resolveFallbackDirection(lastLeadDirection, travelDirection)
        var railDirection = RailPathUtil.computeDirection(currentLocation, fallbackDirection)
        val railType = LocationUtil.getRailType(currentLocation)
        railDirection = KinematicLeadDirection.resolveRailDirection(
            railDirection,
            fallbackDirection,
            lastLeadDirection,
            travelDirection,
            liveLeadSpeed,
            railType,
        )

        val facingDirection = railDirection.clone()
        val targetSpeed = KinematicLeadSpeedPlanner.planTargetSpeed(
            railType,
            baseSpeed,
            safeMode,
            LocationUtil.isPoweredAscendingRailPowered(currentLocation),
            sampleLookaheadRailTypes(currentLocation, railDirection, safeMode, lookaheadBlocks),
        )

        val targetVelocity = railDirection.clone().multiply(targetSpeed)
        var correctedVelocity = KinematicSpacingMath.applySpacingUpdate(targetVelocity, spacingCorrection, maxSpeed)
        correctedVelocity = KinematicRailMotionMath.alignVelocityToRail(
            currentLocation,
            correctedVelocity,
            railDirection,
            lastLeadDirection,
        )
        correctedVelocity = KinematicSpacingMath.clampVelocity(correctedVelocity, targetSpeed)

        val leadState = KinematicLeadStateMath.advanceAndRecover(currentLocation, correctedVelocity, timeFraction)
        return LeadMotionCommand(facingDirection, correctedVelocity, leadState)
    }

    private fun sampleLookaheadRailTypes(
        currentLocation: Location,
        railDirection: Vector,
        safeMode: Boolean,
        lookaheadBlocks: Int,
    ): List<LocationUtil.RailType> {
        val lookaheadTypes = ArrayList<LocationUtil.RailType>()
        if (!safeMode || lookaheadBlocks <= 0) {
            return lookaheadTypes
        }

        val ahead = currentLocation.clone()
        val lookaheadStep = railDirection.clone()
        for (index in 1..lookaheadBlocks) {
            ahead.add(lookaheadStep)
            lookaheadTypes.add(LocationUtil.getRailType(ahead))
        }
        return lookaheadTypes
    }
}
