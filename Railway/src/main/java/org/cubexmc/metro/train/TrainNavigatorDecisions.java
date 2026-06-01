package org.cubexmc.metro.train;

import java.util.List;

final class TrainNavigatorDecisions {

    private TrainNavigatorDecisions() {
    }

    static DepartureDecision resolveDeparture(int stopCount, int currentIndex, int targetIndex) {
        if (stopCount < 2) {
            return DepartureDecision.terminate();
        }

        int resolvedTarget = targetIndex >= 0 ? targetIndex : currentIndex + 1;
        if (resolvedTarget < 0 || resolvedTarget >= stopCount) {
            return DepartureDecision.terminate();
        }

        return DepartureDecision.depart(resolvedTarget);
    }

    static ArrivalDecision resolveArrival(List<String> stopIds, int targetIndex, String arrivedStopId, boolean loopLine) {
        if (stopIds == null || stopIds.isEmpty() || targetIndex < 0 || targetIndex >= stopIds.size()) {
            return ArrivalDecision.invalid();
        }

        int currentIndex = targetIndex;
        boolean terminal = currentIndex >= stopIds.size() - 1;
        if (terminal && loopLine && arrivedStopId != null && arrivedStopId.equals(stopIds.get(0))) {
            currentIndex = 0;
            terminal = false;
        }

        int nextStopIndex = (!terminal && currentIndex < stopIds.size() - 1) ? currentIndex + 1 : -1;
        TrainInstance.TrainState nextState = terminal
                ? TrainInstance.TrainState.TERMINATING
                : TrainInstance.TrainState.WAITING;

        return new ArrivalDecision(true, currentIndex, -1, nextState, terminal, nextStopIndex);
    }

    static final class DepartureDecision {
        final boolean shouldTerminate;
        final int targetIndex;

        private DepartureDecision(boolean shouldTerminate, int targetIndex) {
            this.shouldTerminate = shouldTerminate;
            this.targetIndex = targetIndex;
        }

        static DepartureDecision depart(int targetIndex) {
            return new DepartureDecision(false, targetIndex);
        }

        static DepartureDecision terminate() {
            return new DepartureDecision(true, -1);
        }
    }

    static final class ArrivalDecision {
        final boolean valid;
        final int currentIndex;
        final int targetIndex;
        final TrainInstance.TrainState nextState;
        final boolean terminal;
        final int nextStopIndex;

        private ArrivalDecision(boolean valid, int currentIndex, int targetIndex, TrainInstance.TrainState nextState,
                boolean terminal, int nextStopIndex) {
            this.valid = valid;
            this.currentIndex = currentIndex;
            this.targetIndex = targetIndex;
            this.nextState = nextState;
            this.terminal = terminal;
            this.nextStopIndex = nextStopIndex;
        }

        static ArrivalDecision invalid() {
            return new ArrivalDecision(false, -1, -1, TrainInstance.TrainState.WAITING, false, -1);
        }
    }
}
