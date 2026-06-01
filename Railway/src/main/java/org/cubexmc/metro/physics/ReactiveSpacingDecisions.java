package org.cubexmc.metro.physics;

final class ReactiveSpacingDecisions {

    private static final double KP = 0.8;
    private static final double KD = 0.3;
    private static final double MIN_SPACING = 0.5;
    private static final double NORMAL_SPACING = 1.2;

    private ReactiveSpacingDecisions() {
    }

    static double desiredSpacing(double predecessorAlongSpeed) {
        double trainSpeed = Math.abs(predecessorAlongSpeed);
        if (trainSpeed < 0.05) {
            return MIN_SPACING;
        }
        return Math.min(NORMAL_SPACING, MIN_SPACING + trainSpeed * 0.3);
    }

    static double followerTargetAlongSpeed(double predecessorAlongSpeed, double followerAlongSpeed,
            double distanceToPredecessor, double timeFraction, double maxSpeed) {
        double desiredSpacing = desiredSpacing(predecessorAlongSpeed);
        double relSpeed = predecessorAlongSpeed - followerAlongSpeed;
        double error = distanceToPredecessor - desiredSpacing;

        double accel = (KP * error - KD * relSpeed) * timeFraction;
        double targetAlong = followerAlongSpeed + accel;

        if (error > 0.5) {
            targetAlong += Math.min(error * 0.4, maxSpeed * 0.3);
        } else if (error < -0.2) {
            targetAlong = Math.min(targetAlong, predecessorAlongSpeed * 0.8);
        }

        if (Math.abs(error) < 0.1) {
            targetAlong = predecessorAlongSpeed;
        }

        targetAlong = Math.max(0.0, targetAlong);
        if (targetAlong > maxSpeed) {
            targetAlong = maxSpeed;
        }
        return targetAlong;
    }
}