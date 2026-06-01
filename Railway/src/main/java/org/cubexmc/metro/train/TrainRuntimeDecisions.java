package org.cubexmc.metro.train;

final class TrainRuntimeDecisions {

    private TrainRuntimeDecisions() {
    }

    static boolean shouldKeepChunksLoaded(boolean chunkLoadingEnabled, boolean globalMode, boolean hasAnyPassengers,
            boolean onlyWhenMoving, TrainInstance.TrainState state) {
        if (!chunkLoadingEnabled) {
            return false;
        }

        if (!globalMode && !hasAnyPassengers) {
            return false;
        }

        if (onlyWhenMoving && state != TrainInstance.TrainState.MOVING) {
            return false;
        }

        return true;
    }
}
