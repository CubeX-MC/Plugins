package org.cubexmc.metro.physics

import org.cubexmc.metro.util.LocationUtil
import kotlin.math.max
import kotlin.math.min

internal object KinematicLeadSpeedPlanner {

    @JvmStatic
    fun planTargetSpeed(
        currentRailType: LocationUtil.RailType,
        baseSpeed: Double,
        safeMode: Boolean,
        poweredAscendingRailPowered: Boolean,
        lookaheadRailTypes: List<LocationUtil.RailType>?,
    ): Double {
        var targetSpeed = LocationUtil.getSafeSpeedForRail(currentRailType, baseSpeed, safeMode)
        var minSpeedAhead = targetSpeed

        if (safeMode && !lookaheadRailTypes.isNullOrEmpty()) {
            val lookahead = lookaheadRailTypes.size
            for (index in 0 until lookahead) {
                val aheadType = lookaheadRailTypes[index]
                val aheadSafe = LocationUtil.getSafeSpeedForRail(aheadType, baseSpeed, true)
                minSpeedAhead = min(minSpeedAhead, aheadSafe)
                if (aheadSafe < targetSpeed - 1.0e-4) {
                    val blendFactor = (index + 1).toDouble() / (lookahead + 1)
                    val blended = targetSpeed * blendFactor + aheadSafe * (1.0 - blendFactor)
                    targetSpeed = min(targetSpeed, blended)
                }
            }
        }

        targetSpeed = KinematicSpacingMath.applyTerrainBoost(
            poweredAscendingRailPowered,
            currentRailType,
            targetSpeed,
            baseSpeed,
        )

        if (currentRailType == LocationUtil.RailType.CURVE && minSpeedAhead < targetSpeed) {
            targetSpeed = minSpeedAhead
        }

        return max(0.05, targetSpeed)
    }
}
