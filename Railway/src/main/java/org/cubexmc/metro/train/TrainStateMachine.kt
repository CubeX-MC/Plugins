package org.cubexmc.metro.train

/**
 * Owns state transitions and transition diagnostics for a train session.
 */
class TrainStateMachine(private val session: TrainSession) {
    fun transitionTo(nextState: TrainMovementTask.TrainState, detail: String?): TrainMovementTask.TrainState {
        val previousState = session.state
        session.state = nextState
        session.debug(
            "State transition $previousState -> $nextState" +
                " for passenger=${session.safePassengerName()}" +
                ", currentStop=${session.currentStopId}" +
                ", targetStop=${session.targetStopId}" +
                if (detail == null || detail.isBlank()) "" else ", $detail",
        )
        return previousState
    }
}
