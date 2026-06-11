package org.cubexmc.metro.train

/**
 * Keeps ride scoreboard updates out of movement state transitions.
 */
class TrainScoreboardController {
    fun updateBasedOnState(session: TrainSession) {
        val passenger = session.passenger
        val line = session.line
        if (passenger == null || !passenger.isOnline || line == null) {
            return
        }

        when (session.state) {
            TrainMovementTask.TrainState.STOPPED_AT_STATION -> {
                if (session.targetStopId == null) {
                    session.plugin.scoreboardManager.updateTerminalScoreboard(passenger, line, session.currentStopId)
                } else {
                    session.plugin.scoreboardManager.updateEnteringStopScoreboard(passenger, line, session.currentStopId)
                }
            }
            TrainMovementTask.TrainState.MOVING_IN_STATION ->
                session.plugin.scoreboardManager.updateTravelingScoreboard(passenger, line, session.targetStopId)
            TrainMovementTask.TrainState.MOVING_BETWEEN_STATIONS -> {
            }
        }
    }
}
