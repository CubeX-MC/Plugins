package org.cubexmc.metro.physics;

import org.bukkit.Location;
import org.bukkit.util.Vector;

final class KinematicRailMotionMath {

    private KinematicRailMotionMath() {
    }

    static Vector normalizeOr(Vector primary, Vector fallback) {
        Vector candidate = primary != null ? primary.clone() : null;
        if (candidate != null && candidate.lengthSquared() > 1.0e-8) {
            return candidate.normalize();
        }
        Vector alternate = fallback != null ? fallback.clone() : null;
        if (alternate != null && alternate.lengthSquared() > 1.0e-8) {
            return alternate.normalize();
        }
        return new Vector(1, 0, 0);
    }

    static Vector alignDirectionToRail(Location location, Vector preferredDirection, Vector fallbackDirection) {
        Vector motionDirection = normalizeOr(preferredDirection, fallbackDirection);
        Location snapped = RailPathUtil.project(location.clone());
        Vector railDirection = RailPathUtil.computeDirection(snapped, motionDirection);
        if (railDirection != null && railDirection.lengthSquared() > 1.0e-8) {
            return railDirection.normalize();
        }
        return motionDirection;
    }

    static Vector alignVelocityToRail(Location location, Vector velocity, Vector preferredDirection,
            Vector fallbackDirection) {
        if (velocity == null) {
            return new Vector();
        }

        Vector base = velocity.clone();
        Vector alignedDirection = alignDirectionToRail(location, preferredDirection, fallbackDirection);
        return alignedDirection.multiply(base.length());
    }
}