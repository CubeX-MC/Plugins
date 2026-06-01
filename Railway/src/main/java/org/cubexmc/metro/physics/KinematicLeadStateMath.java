package org.cubexmc.metro.physics;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.cubexmc.metro.util.LocationUtil;

final class KinematicLeadStateMath {

    static final class LeadState {
        final double x;
        final double y;
        final double z;
        final double vx;
        final double vy;
        final double vz;
        final Location location;

        LeadState(double x, double y, double z, double vx, double vy, double vz, Location location) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.location = location;
        }
    }

    private KinematicLeadStateMath() {
    }

    static LeadState advanceAndRecover(Location currentLocation, Vector correctedVelocity, double timeFraction) {
        Vector step = correctedVelocity.clone().multiply(timeFraction);

        double nextX = currentLocation.getX() + step.getX();
        double nextY = currentLocation.getY() + step.getY();
        double nextZ = currentLocation.getZ() + step.getZ();

        Location nextLocation = new Location(currentLocation.getWorld(), nextX, nextY, nextZ);
        if (!LocationUtil.isRail(nextLocation)) {
            Location snapped = LocationUtil.snapToRail(nextLocation, currentLocation.getWorld());
            if (snapped != null && snapped.distanceSquared(nextLocation) < 4.0) {
                nextLocation = snapped;
                nextX = snapped.getX();
                nextY = snapped.getY();
                nextZ = snapped.getZ();
            }
        }

        nextLocation = RailPathUtil.project(nextLocation);
        nextX = nextLocation.getX();
        nextY = nextLocation.getY();
        nextZ = nextLocation.getZ();

        return new LeadState(
                nextX,
                nextY,
                nextZ,
                correctedVelocity.getX(),
                correctedVelocity.getY(),
                correctedVelocity.getZ(),
                nextLocation);
    }
}