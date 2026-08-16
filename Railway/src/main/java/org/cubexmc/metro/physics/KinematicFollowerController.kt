package org.cubexmc.metro.physics

import org.bukkit.Location
import org.bukkit.util.Vector
import kotlin.math.max
import kotlin.math.min

internal object KinematicFollowerController {

    internal class FollowerCommand(
        @JvmField val targetPosition: Location,
        @JvmField val velocity: Vector,
        @JvmField val facing: Vector,
    )

    @JvmStatic
    fun resolveWithoutTrail(
        currentLocation: Location,
        existingVelocity: Vector?,
        fallbackDirection: Vector?,
        carMaxSpeed: Double,
        baseSpeed: Double,
    ): FollowerCommand {
        val projected = RailPathUtil.project(currentLocation)
        val tangent = KinematicRailMotionMath.normalizeOr(existingVelocity, fallbackDirection)
        val corrected = KinematicSpacingMath.applySpacingUpdate(existingVelocity, null, carMaxSpeed)
        var aligned = KinematicRailMotionMath.alignVelocityToRail(
            projected,
            corrected,
            tangent,
            fallbackDirection,
        )
        aligned = KinematicSpacingMath.clampVelocity(aligned, baseSpeed)
        return FollowerCommand(projected, aligned, tangent)
    }

    @JvmStatic
    fun resolveWithSample(
        sample: KinematicTrailBuffer.TrailSample,
        currentVelocity: Vector,
        fallbackDirection: Vector?,
        baseSpeed: Double,
    ): FollowerCommand {
        val targetPosition = RailPathUtil.project(sample.location)
        val motionDirection = KinematicRailMotionMath.normalizeOr(sample.tangent, fallbackDirection)

        val configuredMax = max(0.05, baseSpeed)
        var sampleSpeed = min(sample.speed, configuredMax)
        if (sampleSpeed < 1.0e-6) {
            sampleSpeed = min(currentVelocity.length(), configuredMax)
        }

        val baseVelocity = motionDirection.clone().multiply(sampleSpeed)
        var aligned = KinematicRailMotionMath.alignVelocityToRail(
            targetPosition,
            baseVelocity,
            motionDirection,
            fallbackDirection,
        )
        aligned = KinematicSpacingMath.clampVelocity(aligned, baseSpeed)
        val facing = KinematicRailMotionMath.normalizeOr(aligned, motionDirection)
        return FollowerCommand(targetPosition, aligned, facing)
    }
}
