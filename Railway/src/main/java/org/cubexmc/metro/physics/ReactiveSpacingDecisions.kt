package org.cubexmc.metro.physics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object ReactiveSpacingDecisions {

    private const val KP = 0.8
    private const val KD = 0.3
    private const val MIN_SPACING = 0.5
    private const val NORMAL_SPACING = 1.2

    @JvmStatic
    fun desiredSpacing(predecessorAlongSpeed: Double): Double {
        val trainSpeed = abs(predecessorAlongSpeed)
        if (trainSpeed < 0.05) {
            return MIN_SPACING
        }
        return min(NORMAL_SPACING, MIN_SPACING + trainSpeed * 0.3)
    }

    @JvmStatic
    fun followerTargetAlongSpeed(
        predecessorAlongSpeed: Double,
        followerAlongSpeed: Double,
        distanceToPredecessor: Double,
        timeFraction: Double,
        maxSpeed: Double,
    ): Double {
        val desiredSpacing = desiredSpacing(predecessorAlongSpeed)
        val relativeSpeed = predecessorAlongSpeed - followerAlongSpeed
        val error = distanceToPredecessor - desiredSpacing

        val acceleration = (KP * error - KD * relativeSpeed) * timeFraction
        var targetAlong = followerAlongSpeed + acceleration

        if (error > 0.5) {
            targetAlong += min(error * 0.4, maxSpeed * 0.3)
        } else if (error < -0.2) {
            targetAlong = min(targetAlong, predecessorAlongSpeed * 0.8)
        }

        if (abs(error) < 0.1) {
            targetAlong = predecessorAlongSpeed
        }

        targetAlong = max(0.0, targetAlong)
        if (targetAlong > maxSpeed) {
            targetAlong = maxSpeed
        }
        return targetAlong
    }
}
