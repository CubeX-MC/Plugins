package org.cubexmc.metro.physics;

import org.bukkit.Location;
import org.bukkit.util.Vector;

final class KinematicFollowerController {

    static final class FollowerCommand {
        final Location targetPosition;
        final Vector velocity;
        final Vector facing;

        FollowerCommand(Location targetPosition, Vector velocity, Vector facing) {
            this.targetPosition = targetPosition;
            this.velocity = velocity;
            this.facing = facing;
        }
    }

    private KinematicFollowerController() {
    }

    static FollowerCommand resolveWithoutTrail(Location currentLocation, Vector existingVelocity,
            Vector fallbackDirection, double carMaxSpeed, double baseSpeed) {
        Location projected = RailPathUtil.project(currentLocation);
        Vector tangent = KinematicRailMotionMath.normalizeOr(existingVelocity, fallbackDirection);
        Vector corrected = KinematicSpacingMath.applySpacingUpdate(existingVelocity, null, carMaxSpeed);
        Vector aligned = KinematicRailMotionMath.alignVelocityToRail(projected, corrected, tangent, fallbackDirection);
        aligned = KinematicSpacingMath.clampVelocity(aligned, baseSpeed);
        return new FollowerCommand(projected, aligned, tangent);
    }

    static FollowerCommand resolveWithSample(KinematicTrailBuffer.TrailSample sample, Vector currentVelocity,
            Vector fallbackDirection, double baseSpeed) {
        Location targetPosition = RailPathUtil.project(sample.location);
        Vector motionDirection = KinematicRailMotionMath.normalizeOr(sample.tangent, fallbackDirection);

        double configuredMax = Math.max(0.05, baseSpeed);
        double sampleSpeed = Math.min(sample.speed, configuredMax);
        if (sampleSpeed < 1.0e-6) {
            sampleSpeed = Math.min(currentVelocity.length(), configuredMax);
        }

        Vector baseVelocity = motionDirection.clone().multiply(sampleSpeed);
        Vector aligned = KinematicRailMotionMath.alignVelocityToRail(
                targetPosition,
                baseVelocity,
                motionDirection,
                fallbackDirection);
        aligned = KinematicSpacingMath.clampVelocity(aligned, baseSpeed);
        Vector facing = KinematicRailMotionMath.normalizeOr(aligned, motionDirection);
        return new FollowerCommand(targetPosition, aligned, facing);
    }
}