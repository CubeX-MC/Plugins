package org.cubexmc.metro.physics;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.cubexmc.metro.util.LocationUtil;

final class KinematicLeadMotionPlanner {

    static final class LeadMotionCommand {
        final Vector facingDirection;
        final Vector correctedVelocity;
        final KinematicLeadStateMath.LeadState leadState;

        LeadMotionCommand(Vector facingDirection, Vector correctedVelocity, KinematicLeadStateMath.LeadState leadState) {
            this.facingDirection = facingDirection;
            this.correctedVelocity = correctedVelocity;
            this.leadState = leadState;
        }
    }

    private KinematicLeadMotionPlanner() {
    }

    static LeadMotionCommand plan(Location currentLocation, Vector lastLeadDirection, Vector travelDirection,
            double liveLeadSpeed, double baseSpeed, boolean safeMode, int lookaheadBlocks, double maxSpeed,
            Vector spacingCorrection, double timeFraction) {
        Vector fallbackDirection = KinematicLeadDirection.resolveFallbackDirection(lastLeadDirection, travelDirection);
        Vector railDirection = RailPathUtil.computeDirection(currentLocation, fallbackDirection);
        LocationUtil.RailType railType = LocationUtil.getRailType(currentLocation);
        railDirection = KinematicLeadDirection.resolveRailDirection(
                railDirection,
                fallbackDirection,
                lastLeadDirection,
                travelDirection,
                liveLeadSpeed,
                railType);

        Vector facingDirection = railDirection.clone();
        double targetSpeed = KinematicLeadSpeedPlanner.planTargetSpeed(
                railType,
                baseSpeed,
                safeMode,
                LocationUtil.isPoweredAscendingRailPowered(currentLocation),
                sampleLookaheadRailTypes(currentLocation, railDirection, safeMode, lookaheadBlocks));

        Vector targetVelocity = railDirection.clone().multiply(targetSpeed);
        Vector correctedVelocity = KinematicSpacingMath.applySpacingUpdate(targetVelocity, spacingCorrection, maxSpeed);
        correctedVelocity = KinematicRailMotionMath.alignVelocityToRail(
                currentLocation,
                correctedVelocity,
                railDirection,
                lastLeadDirection);
        correctedVelocity = KinematicSpacingMath.clampVelocity(correctedVelocity, targetSpeed);

        KinematicLeadStateMath.LeadState leadState = KinematicLeadStateMath.advanceAndRecover(
                currentLocation,
                correctedVelocity,
                timeFraction);

        return new LeadMotionCommand(facingDirection, correctedVelocity, leadState);
    }

    private static List<LocationUtil.RailType> sampleLookaheadRailTypes(Location currentLocation, Vector railDirection,
            boolean safeMode, int lookaheadBlocks) {
        List<LocationUtil.RailType> lookaheadTypes = new ArrayList<>();
        if (!safeMode || lookaheadBlocks <= 0) {
            return lookaheadTypes;
        }

        Location ahead = currentLocation.clone();
        Vector lookaheadStep = railDirection.clone();
        for (int i = 1; i <= lookaheadBlocks; i++) {
            ahead.add(lookaheadStep);
            lookaheadTypes.add(LocationUtil.getRailType(ahead));
        }
        return lookaheadTypes;
    }
}