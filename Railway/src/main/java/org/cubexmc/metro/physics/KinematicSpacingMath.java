package org.cubexmc.metro.physics;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.cubexmc.metro.util.LocationUtil;

final class KinematicSpacingMath {

    private static final double CART_DISTANCE_FORCER = 0.1;

    private KinematicSpacingMath() {
    }

    static double applyTerrainBoost(boolean poweredAscendingRailPowered, LocationUtil.RailType railType,
            double currentSpeed, double baseSpeed) {
        if (poweredAscendingRailPowered) {
            double minSpeed = Math.max(0.4, baseSpeed * 0.8);
            return Math.max(currentSpeed, minSpeed);
        }

        if (railType == LocationUtil.RailType.CURVE) {
            double criticalMin = Math.max(0.15, baseSpeed * 0.5);
            return Math.max(currentSpeed, criticalMin);
        }

        return currentSpeed;
    }

    static Vector calculateLeadCorrection(Location leadLocation, Location followerLocation, double spacing,
            Vector fallbackDirection) {
        if (leadLocation == null || followerLocation == null) {
            return new Vector(0, 0, 0);
        }

        Vector direction = followerLocation.toVector().subtract(leadLocation.toVector());
        double actualGap = direction.length();

        if (actualGap < 1.0e-6) {
            Vector fallback = fallbackDirection != null ? fallbackDirection.clone() : new Vector(1, 0, 0);
            if (fallback.lengthSquared() < 1.0e-8) {
                fallback = new Vector(1, 0, 0);
            }
            direction = fallback.normalize();
            actualGap = spacing;
        } else {
            direction.normalize();
        }

        double gapError = actualGap - spacing;
        Vector correction = direction.multiply(gapError);

        double maxCorrection = spacing * 0.5;
        double corrLen = correction.length();
        if (corrLen > maxCorrection) {
            correction.multiply(maxCorrection / corrLen);
        }

        return correction;
    }

    static Vector applySpacingUpdate(Vector baseVelocity, Vector correction, double maxSpeed) {
        Vector result = baseVelocity != null ? baseVelocity.clone() : new Vector();
        if (correction == null || correction.lengthSquared() < 1.0e-8) {
            return result;
        }

        double motLen = result.length();
        double effectiveSpeed = Math.max(motLen, 0.2);
        double safeMaxSpeed = Math.max(0.05, maxSpeed);
        double factor = effectiveSpeed / safeMaxSpeed;

        result.setX(result.getX() + factor * correction.getX() * CART_DISTANCE_FORCER);
        result.setZ(result.getZ() + factor * correction.getZ() * CART_DISTANCE_FORCER);
        return result;
    }

    static Vector clampVelocity(Vector velocity, double limit) {
        Vector result = velocity != null ? velocity.clone() : new Vector();
        double safeLimit = Math.max(0.05, limit);
        double len = result.length();
        if (len > safeLimit) {
            result.multiply(safeLimit / len);
        }
        return result;
    }
}