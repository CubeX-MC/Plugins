package org.cubexmc.metro.physics;

import org.bukkit.Location;
import org.bukkit.util.Vector;

final class KinematicSnapMath {

    static final class SnapCommand {
        final Location location;
        final Vector velocity;
        final float yaw;
        final float pitch;

        SnapCommand(Location location, Vector velocity, float yaw, float pitch) {
            this.location = location;
            this.velocity = velocity;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private KinematicSnapMath() {
    }

    static SnapCommand prepare(Location location, Vector velocity, Vector facing, Vector fallbackDirection, float oldYaw) {
        double velocityMagnitude = velocity != null ? velocity.length() : 0.0;
        Vector motionDirection = resolveMotionDirection(velocity, facing, fallbackDirection);

        Location targetLocation = RailPathUtil.project(location.clone());
        motionDirection = KinematicRailMotionMath.alignDirectionToRail(targetLocation, motionDirection, fallbackDirection);

        Vector targetVelocity = motionDirection.clone().multiply(velocityMagnitude);
        Rotation rotation = wrapRotation(motionDirection, oldYaw);
        targetLocation.setYaw(rotation.yaw);
        targetLocation.setPitch(rotation.pitch);
        return new SnapCommand(targetLocation, targetVelocity, rotation.yaw, rotation.pitch);
    }

    private static Vector resolveMotionDirection(Vector velocity, Vector facing, Vector fallbackDirection) {
        return KinematicRailMotionMath.normalizeOr(
                velocity != null && velocity.lengthSquared() > 1.0e-6 ? velocity : facing,
                fallbackDirection);
    }

    private static Rotation wrapRotation(Vector motionDirection, float oldYaw) {
        double dx = motionDirection.getX();
        double dy = motionDirection.getY();
        double dz = motionDirection.getZ();
        double horizontalLength = Math.sqrt(dx * dx + dz * dz);
        float newYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float newPitch = (float) Math.toDegrees(Math.atan2(-dy, horizontalLength));

        while ((newYaw - oldYaw) >= 90.0f) {
            newYaw -= 180.0f;
            newPitch = -newPitch;
        }
        while ((newYaw - oldYaw) < -90.0f) {
            newYaw += 180.0f;
            newPitch = -newPitch;
        }
        while ((newYaw - oldYaw) <= -180.0f) {
            newYaw += 360.0f;
        }
        while ((newYaw - oldYaw) > 180.0f) {
            newYaw -= 360.0f;
        }

        return new Rotation(newYaw, newPitch);
    }

    private record Rotation(float yaw, float pitch) {
    }
}