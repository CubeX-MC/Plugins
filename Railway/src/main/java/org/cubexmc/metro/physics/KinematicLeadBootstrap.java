package org.cubexmc.metro.physics;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.cubexmc.metro.util.LocationUtil;

final class KinematicLeadBootstrap {

    record BootstrapState(Location location, Vector velocity, Vector facingDirection) {
    }

    private KinematicLeadBootstrap() {
    }

    static BootstrapState initialize(Location location, Vector travelDirection, Vector currentVelocity, double speed) {
        Location resolvedLocation = location != null ? location.clone() : null;
        if (resolvedLocation != null && resolvedLocation.getWorld() != null) {
            Location snapped = LocationUtil.snapToRail(resolvedLocation, resolvedLocation.getWorld());
            if (snapped != null) {
                resolvedLocation = snapped;
            }
        }

        Vector resolvedVelocity;
        Vector resolvedFacing;
        if (travelDirection != null && travelDirection.lengthSquared() > 1.0e-6) {
            resolvedFacing = travelDirection.clone().normalize();
            resolvedVelocity = resolvedFacing.clone().multiply(speed);
        } else if (currentVelocity != null && currentVelocity.lengthSquared() > 1.0e-6) {
            resolvedVelocity = currentVelocity.clone();
            resolvedFacing = currentVelocity.clone().normalize();
        } else {
            resolvedVelocity = new Vector();
            resolvedFacing = new Vector(1, 0, 0);
        }

        if (resolvedLocation == null) {
            resolvedLocation = new Location(null, 0.0, 0.0, 0.0);
        }
        return new BootstrapState(resolvedLocation, resolvedVelocity, resolvedFacing);
    }

    static Vector resolveSeedVelocity(Vector authoritativeVelocity, Vector lastLeadDirection, double speed) {
        if (authoritativeVelocity != null && authoritativeVelocity.lengthSquared() > 1.0e-6) {
            return authoritativeVelocity.clone();
        }
        Vector fallbackDirection = KinematicRailMotionMath.normalizeOr(lastLeadDirection, new Vector(1, 0, 0));
        return fallbackDirection.multiply(speed);
    }
}