package org.cubexmc.metro.physics;

import org.bukkit.util.Vector;
import org.cubexmc.metro.util.LocationUtil;

final class KinematicLeadDirection {

    private KinematicLeadDirection() {
    }

    static Vector resolveFallbackDirection(Vector lastLeadDirection, Vector travelDirection) {
        if (lastLeadDirection != null && lastLeadDirection.lengthSquared() > 1.0e-6) {
            return lastLeadDirection.clone().normalize();
        }
        if (travelDirection != null && travelDirection.lengthSquared() > 1.0e-6) {
            return travelDirection.clone().normalize();
        }
        return new Vector(1, 0, 0);
    }

    static Vector resolveRailDirection(Vector railDirection, Vector fallbackDirection, Vector lastLeadDirection,
            Vector travelDirection, double currentSpeed, LocationUtil.RailType railType) {
        Vector resolved = railDirection != null ? railDirection.clone() : null;
        Vector fallback = fallbackDirection != null ? fallbackDirection.clone() : new Vector(1, 0, 0);
        if (fallback.lengthSquared() < 1.0e-6) {
            fallback = new Vector(1, 0, 0);
        }

        if (resolved == null || resolved.lengthSquared() < 1.0e-6) {
            return fallback.normalize();
        }

        resolved.normalize();
        if (currentSpeed < 0.2 && travelDirection != null && travelDirection.lengthSquared() > 1.0e-6
                && resolved.dot(travelDirection) < 0.0) {
            resolved.multiply(-1.0);
        }

        boolean isSlope = railType == LocationUtil.RailType.ASCENDING
                || railType == LocationUtil.RailType.DESCENDING;
        double blendFactor = isSlope ? 0.85 : 0.4;
        Vector previous = lastLeadDirection != null ? lastLeadDirection.clone() : null;
        if (previous != null && previous.lengthSquared() > 1.0e-6) {
            previous.normalize();
            double dot = resolved.dot(previous);
            if (dot < 0.0) {
                previous.multiply(-1.0);
                dot = resolved.dot(previous);
            }
            if (dot < (isSlope ? 0.99 : 0.707)) {
                resolved = previous.multiply(1.0 - blendFactor).add(resolved.multiply(blendFactor)).normalize();
            }
        }

        return resolved;
    }
}