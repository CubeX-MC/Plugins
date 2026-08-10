package org.cubexmc.metro.train

internal object TrainRuntimeDecisions {

    @JvmStatic
    fun shouldKeepChunksLoaded(
        chunkLoadingEnabled: Boolean,
        globalMode: Boolean,
        hasAnyPassengers: Boolean,
        onlyWhenMoving: Boolean,
        state: TrainInstance.TrainState?,
    ): Boolean {
        if (!chunkLoadingEnabled) {
            return false
        }

        if (!globalMode && !hasAnyPassengers) {
            return false
        }

        if (onlyWhenMoving && state != TrainInstance.TrainState.MOVING) {
            return false
        }

        return true
    }
}
