package org.cubexmc.metro.physics

import org.bukkit.Location
import org.bukkit.util.Vector
import kotlin.math.atan2
import kotlin.math.sqrt

internal object KinematicSnapMath {

    internal class SnapCommand(
        @JvmField val location: Location,
        @JvmField val velocity: Vector,
        @JvmField val yaw: Float,
        @JvmField val pitch: Float,
    )

    @JvmStatic
    fun prepare(
        location: Location,
        velocity: Vector?,
        facing: Vector?,
        fallbackDirection: Vector?,
        oldYaw: Float,
    ): SnapCommand {
        val velocityMagnitude = velocity?.length() ?: 0.0
        var motionDirection = resolveMotionDirection(velocity, facing, fallbackDirection)

        val targetLocation = RailPathUtil.project(location.clone())
        motionDirection = KinematicRailMotionMath.alignDirectionToRail(
            targetLocation,
            motionDirection,
            fallbackDirection,
        )

        val targetVelocity = motionDirection.clone().multiply(velocityMagnitude)
        val rotation = wrapRotation(motionDirection, oldYaw)
        targetLocation.yaw = rotation.yaw
        targetLocation.pitch = rotation.pitch
        return SnapCommand(targetLocation, targetVelocity, rotation.yaw, rotation.pitch)
    }

    private fun resolveMotionDirection(velocity: Vector?, facing: Vector?, fallbackDirection: Vector?): Vector =
        KinematicRailMotionMath.normalizeOr(
            if (velocity != null && velocity.lengthSquared() > 1.0e-6) velocity else facing,
            fallbackDirection,
        )

    private fun wrapRotation(motionDirection: Vector, oldYaw: Float): Rotation {
        val dx = motionDirection.x
        val dy = motionDirection.y
        val dz = motionDirection.z
        val horizontalLength = sqrt(dx * dx + dz * dz)
        var newYaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        var newPitch = Math.toDegrees(atan2(-dy, horizontalLength)).toFloat()

        while (newYaw - oldYaw >= 90.0f) {
            newYaw -= 180.0f
            newPitch = -newPitch
        }
        while (newYaw - oldYaw < -90.0f) {
            newYaw += 180.0f
            newPitch = -newPitch
        }
        while (newYaw - oldYaw <= -180.0f) {
            newYaw += 360.0f
        }
        while (newYaw - oldYaw > 180.0f) {
            newYaw -= 360.0f
        }

        return Rotation(newYaw, newPitch)
    }

    private data class Rotation(val yaw: Float, val pitch: Float)
}
