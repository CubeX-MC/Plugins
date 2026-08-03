package org.cubexmc.metro.train

internal object TrainNavigatorDecisions {
    @JvmStatic
    fun resolveDeparture(stopCount: Int, currentIndex: Int, targetIndex: Int): DepartureDecision {
        if (stopCount < 2) {
            return DepartureDecision.terminate()
        }

        val resolvedTarget = if (targetIndex >= 0) targetIndex else currentIndex + 1
        if (resolvedTarget < 0 || resolvedTarget >= stopCount) {
            return DepartureDecision.terminate()
        }

        return DepartureDecision.depart(resolvedTarget)
    }

    @JvmStatic
    fun resolveArrival(
        stopIds: List<String>?,
        targetIndex: Int,
        arrivedStopId: String?,
        loopLine: Boolean,
    ): ArrivalDecision {
        if (stopIds.isNullOrEmpty() || targetIndex < 0 || targetIndex >= stopIds.size) {
            return ArrivalDecision.invalid()
        }

        var currentIndex = targetIndex
        var terminal = currentIndex >= stopIds.size - 1
        if (terminal && loopLine && arrivedStopId != null && arrivedStopId == stopIds[0]) {
            currentIndex = 0
            terminal = false
        }

        val nextStopIndex = if (!terminal && currentIndex < stopIds.size - 1) currentIndex + 1 else -1
        val nextState = if (terminal) {
            TrainInstance.TrainState.TERMINATING
        } else {
            TrainInstance.TrainState.WAITING
        }

        return ArrivalDecision(true, currentIndex, -1, nextState, terminal, nextStopIndex)
    }

    internal class DepartureDecision private constructor(
        @JvmField val shouldTerminate: Boolean,
        @JvmField val targetIndex: Int,
    ) {
        companion object {
            @JvmStatic
            fun depart(targetIndex: Int): DepartureDecision = DepartureDecision(false, targetIndex)

            @JvmStatic
            fun terminate(): DepartureDecision = DepartureDecision(true, -1)
        }
    }

    internal class ArrivalDecision(
        @JvmField val valid: Boolean,
        @JvmField val currentIndex: Int,
        @JvmField val targetIndex: Int,
        @JvmField val nextState: TrainInstance.TrainState,
        @JvmField val terminal: Boolean,
        @JvmField val nextStopIndex: Int,
    ) {
        companion object {
            @JvmStatic
            fun invalid(): ArrivalDecision =
                ArrivalDecision(false, -1, -1, TrainInstance.TrainState.WAITING, false, -1)
        }
    }
}
