package org.cubexmc.metro.train;

import java.util.List;

final class TrainStateMath {

    @FunctionalInterface
    interface SegmentSecondsLookup {
        double estimateSeconds(String fromStopId, String toStopId);
    }

    private TrainStateMath() {
    }

    static double estimateEtaSecondsToStop(TrainInstance.TrainState state, List<String> stopIds, int currentIndex,
            int targetIndex, boolean loop, String stopId, double segmentElapsedSeconds,
            SegmentSecondsLookup segmentSecondsLookup) {
        if (stopId == null || segmentSecondsLookup == null || stopIds == null || stopIds.size() < 2) {
            return Double.POSITIVE_INFINITY;
        }

        if (state == TrainInstance.TrainState.WAITING) {
            if (currentIndex >= 0 && currentIndex < stopIds.size() && stopId.equals(stopIds.get(currentIndex))) {
                return 0.0;
            }
            return sumTravelSecondsUntilStop(stopIds, currentIndex, loop, stopId, segmentSecondsLookup);
        }

        if (state == TrainInstance.TrainState.MOVING) {
            if (targetIndex < 0 || targetIndex >= stopIds.size() || currentIndex < 0 || currentIndex >= stopIds.size()) {
                return Double.POSITIVE_INFINITY;
            }

            double segmentTotal = Math.max(0.0,
                    segmentSecondsLookup.estimateSeconds(stopIds.get(currentIndex), stopIds.get(targetIndex)));
            double remaining = Math.max(0.0, segmentTotal - Math.max(0.0, segmentElapsedSeconds));
            if (stopId.equals(stopIds.get(targetIndex))) {
                return remaining;
            }

            double downstream = sumTravelSecondsUntilStop(stopIds, targetIndex, loop, stopId, segmentSecondsLookup);
            if (!Double.isFinite(downstream)) {
                return Double.POSITIVE_INFINITY;
            }
            return remaining + downstream;
        }

        return Double.POSITIVE_INFINITY;
    }

    static double estimateVirtualProgress(TrainInstance.TrainState state, int currentIndex, int targetIndex,
            List<String> stopIds, double segmentElapsedSeconds, SegmentSecondsLookup segmentSecondsLookup) {
        if (state != TrainInstance.TrainState.MOVING || targetIndex < 0 || stopIds == null
                || currentIndex < 0 || currentIndex >= stopIds.size() || targetIndex >= stopIds.size()
                || segmentSecondsLookup == null) {
            return 0.0;
        }

        double segmentTotal = Math.max(0.0,
                segmentSecondsLookup.estimateSeconds(stopIds.get(currentIndex), stopIds.get(targetIndex)));
        if (segmentTotal <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, Math.max(0.0, segmentElapsedSeconds) / segmentTotal);
    }

    static boolean isVirtualWaitingState(TrainInstance.TrainState state) {
        return state == TrainInstance.TrainState.WAITING || state == TrainInstance.TrainState.TERMINATING;
    }

    private static double sumTravelSecondsUntilStop(List<String> stopIds, int fromIndex, boolean loop,
            String destinationStopId, SegmentSecondsLookup segmentSecondsLookup) {
        if (fromIndex < 0 || fromIndex >= stopIds.size()) {
            return Double.POSITIVE_INFINITY;
        }

        int size = stopIds.size();
        double sum = 0.0;
        int index = fromIndex;
        int maxSteps = loop ? Math.max(1, size) : Math.max(1, size - 1 - fromIndex);

        for (int steps = 0; steps < maxSteps; steps++) {
            int next = nextArrivalIndex(index, size, loop, stopIds);
            if (next < 0) {
                return Double.POSITIVE_INFINITY;
            }
            sum += Math.max(0.0, segmentSecondsLookup.estimateSeconds(stopIds.get(index), stopIds.get(next)));
            if (destinationStopId.equals(stopIds.get(next))) {
                return sum;
            }
            index = next;
        }

        return Double.POSITIVE_INFINITY;
    }

    private static int nextArrivalIndex(int currentIndex, int size, boolean loop, List<String> stopIds) {
        if (currentIndex < 0 || currentIndex >= size) {
            return -1;
        }

        int next = currentIndex + 1;
        if (next < size) {
            return next;
        }

        if (!loop) {
            return -1;
        }

        boolean repeatedTerminal = size > 1 && stopIds.get(0) != null && stopIds.get(0).equals(stopIds.get(size - 1));
        if (repeatedTerminal) {
            return size > 2 ? 1 : 0;
        }
        return 0;
    }
}