package org.cubexmc.metro.service.strategy

import org.cubexmc.metro.service.DispatchStrategy
import org.cubexmc.metro.service.LineService

class GlobalDispatchStrategy : DispatchStrategy {
    override fun tick(service: LineService, currentTick: Long) {
        if (!service.isDepartureWindow(currentTick)) {
            return
        }
        if (service.isLoopLine && isLoopTrainArrivingSoon(service, currentTick)) {
            return
        }

        service.markDeparture(currentTick)
        service.spawnTrain(currentTick)
    }

    private fun isLoopTrainArrivingSoon(service: LineService, currentTick: Long): Boolean {
        if (!service.isLoopLine) {
            return false
        }
        val line = service.line ?: return false
        val ordered = line.orderedStopIds
        if (ordered.isEmpty()) {
            return false
        }
        val startStopId = ordered[0]
        val threshold = maxOf(2.0, service.headwaySeconds * 0.5)
        for (train in service.activeTrains) {
            val eta = train.estimateEtaSecondsToStop(
                startStopId,
                currentTick,
                service.plugin.travelTimeEstimator,
            )
            if (eta.isFinite() && eta <= threshold) {
                return true
            }
        }
        return false
    }
}
