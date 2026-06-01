package org.cubexmc.metro.service;

import java.util.List;
import java.util.function.ToDoubleBiFunction;

final class ServiceEtaCalculator {

    private ServiceEtaCalculator() {
    }

    static double estimateScheduledEtaSeconds(List<String> stopIds, String targetStopId, int dwellTicks,
            double departureEtaSeconds, ToDoubleBiFunction<String, String> segmentEtaSeconds) {
        if (stopIds == null || stopIds.isEmpty() || targetStopId == null || targetStopId.isEmpty()) {
            return Math.max(0.0, departureEtaSeconds);
        }

        int targetIndex = stopIds.indexOf(targetStopId);
        if (targetIndex <= 0) {
            return Math.max(0.0, departureEtaSeconds);
        }

        double etaSeconds = Math.max(0.0, departureEtaSeconds);
        double dwellSeconds = Math.max(20, dwellTicks) / 20.0;
        for (int i = 0; i < targetIndex; i++) {
            etaSeconds += dwellSeconds;
            etaSeconds += Math.max(0.0, segmentEtaSeconds.applyAsDouble(stopIds.get(i), stopIds.get(i + 1)));
        }
        return etaSeconds;
    }
}