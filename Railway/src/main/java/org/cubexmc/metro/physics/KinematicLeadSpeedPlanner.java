package org.cubexmc.metro.physics;

import java.util.List;

import org.cubexmc.metro.util.LocationUtil;

final class KinematicLeadSpeedPlanner {

    private KinematicLeadSpeedPlanner() {
    }

    static double planTargetSpeed(LocationUtil.RailType currentRailType, double baseSpeed, boolean safeMode,
            boolean poweredAscendingRailPowered, List<LocationUtil.RailType> lookaheadRailTypes) {
        double targetSpeed = LocationUtil.getSafeSpeedForRail(currentRailType, baseSpeed, safeMode);
        double minSpeedAhead = targetSpeed;

        if (safeMode && lookaheadRailTypes != null && !lookaheadRailTypes.isEmpty()) {
            int lookahead = lookaheadRailTypes.size();
            for (int i = 0; i < lookahead; i++) {
                LocationUtil.RailType aheadType = lookaheadRailTypes.get(i);
                double aheadSafe = LocationUtil.getSafeSpeedForRail(aheadType, baseSpeed, true);
                minSpeedAhead = Math.min(minSpeedAhead, aheadSafe);
                if (aheadSafe < targetSpeed - 1.0e-4) {
                    double blendFactor = (double) (i + 1) / (lookahead + 1);
                    double blended = targetSpeed * blendFactor + aheadSafe * (1.0 - blendFactor);
                    targetSpeed = Math.min(targetSpeed, blended);
                }
            }
        }

        targetSpeed = KinematicSpacingMath.applyTerrainBoost(
                poweredAscendingRailPowered,
                currentRailType,
                targetSpeed,
                baseSpeed);

        if (currentRailType == LocationUtil.RailType.CURVE && minSpeedAhead < targetSpeed) {
            targetSpeed = minSpeedAhead;
        }

        return Math.max(0.05, targetSpeed);
    }
}