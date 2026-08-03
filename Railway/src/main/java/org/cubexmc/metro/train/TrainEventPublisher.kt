package org.cubexmc.metro.train

import org.bukkit.Bukkit
import org.cubexmc.metro.event.MetroTrainArrivalEvent
import org.cubexmc.metro.event.MetroTrainDepartureEvent
import org.cubexmc.metro.model.Stop

/**
 * Publishes train lifecycle events consumed by display and integration layers.
 */
class TrainEventPublisher(private val session: TrainSession) {
    fun publishEnteringStop(targetStop: Stop?) {
        val passenger = session.passenger
        val line = session.line
        if (passenger == null || !passenger.isOnline || line == null || targetStop == null) {
            return
        }

        val nextStopId = line.getNextStopId(session.targetStopId)
        val isTerminus = nextStopId == null
        Bukkit.getPluginManager().callEvent(
            MetroTrainArrivalEvent(
                session.minecart,
                passenger,
                line,
                targetStop,
                isTerminus,
                MetroTrainArrivalEvent.ArrivalType.ENTERING,
            ),
        )
    }

    fun publishDockedAtStop(currentStop: Stop?, isTerminus: Boolean) {
        val line = session.line
        if (line == null || currentStop == null) {
            return
        }
        Bukkit.getPluginManager().callEvent(
            MetroTrainArrivalEvent(
                session.minecart,
                session.passenger,
                line,
                currentStop,
                isTerminus,
                MetroTrainArrivalEvent.ArrivalType.DOCKED,
            ),
        )
    }

    fun publishDeparture(currentStop: Stop?, nextStop: Stop?) {
        val line = session.line
        if (line == null || currentStop == null || nextStop == null) {
            return
        }
        Bukkit.getPluginManager().callEvent(
            MetroTrainDepartureEvent(session.minecart, session.passenger, line, currentStop, nextStop),
        )
    }
}
