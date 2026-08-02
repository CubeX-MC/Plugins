package org.cubexmc.metro.service

import java.util.function.ToDoubleBiFunction
import kotlin.math.max

internal object ServiceEtaCalculator {

    private const val TICKS_PER_SECOND = 20.0
    private const val MIN_DWELL_TICKS = 20

    @JvmStatic
    fun estimateScheduledEtaSeconds(
        stopIds: List<String>?,
        targetStopId: String?,
        dwellTicks: Int,
        departureEtaSeconds: Double,
        segmentEtaSeconds: ToDoubleBiFunction<String, String>,
    ): Double {
        if (stopIds.isNullOrEmpty() || targetStopId.isNullOrEmpty()) {
            return max(0.0, departureEtaSeconds)
        }

        val targetIndex = stopIds.indexOf(targetStopId)
        if (targetIndex <= 0) {
            return max(0.0, departureEtaSeconds)
        }

        var etaSeconds = max(0.0, departureEtaSeconds)
        val dwellSeconds = max(MIN_DWELL_TICKS, dwellTicks) / TICKS_PER_SECOND
        for (i in 0 until targetIndex) {
            etaSeconds += dwellSeconds
            etaSeconds += max(0.0, segmentEtaSeconds.applyAsDouble(stopIds[i], stopIds[i + 1]))
        }
        return etaSeconds
    }
}
