package org.cubexmc.metro.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.cubexmc.metro.util.LocationUtil;
import org.junit.jupiter.api.Test;

class KinematicPhysicsMigrationTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void preservesStaticHelpersAndBootstrapRecordShape() throws Exception {
        assertTrue(KinematicLeadBootstrap.BootstrapState.class.isRecord());
        assertTrue(Modifier.isStatic(KinematicLeadBootstrap.class
                .getDeclaredMethod("initialize", Location.class, Vector.class, Vector.class, double.class)
                .getModifiers()));

        KinematicLeadBootstrap.BootstrapState state = KinematicLeadBootstrap.initialize(null, null, null, 0.4);

        assertEquals(0.0, state.location().getX(), EPSILON);
        assertEquals(0.0, state.velocity().lengthSquared(), EPSILON);
        assertEquals(new Vector(1, 0, 0), state.facingDirection());
        assertTrue(TrainPhysicsEngine.class.isAssignableFrom(KinematicRailPhysics.class));
        assertFalse(Modifier.isFinal(KinematicRailPhysics.class.getModifiers()));
    }

    @Test
    void keepsDirectionAndSpacingCalculationsStable() {
        Vector fallback = new Vector(0, 0, 2);
        Vector normalized = KinematicRailMotionMath.normalizeOr(null, fallback);
        assertEquals(new Vector(0, 0, 1), normalized);
        assertEquals(new Vector(0, 0, 2), fallback);

        Vector correction = KinematicSpacingMath.calculateLeadCorrection(
                new Location(null, 0, 0, 0),
                new Location(null, 4, 0, 0),
                2.0,
                null);
        assertEquals(new Vector(1, 0, 0), correction);

        Vector updated = KinematicSpacingMath.applySpacingUpdate(new Vector(0.4, 0, 0), correction, 0.4);
        assertEquals(0.5, updated.getX(), EPSILON);

        Vector clamped = KinematicSpacingMath.clampVelocity(new Vector(3, 0, 4), 2.0);
        assertEquals(2.0, clamped.length(), EPSILON);
    }

    @Test
    void keepsLookaheadSpeedPlanningAndTerrainBoostRules() {
        double planned = KinematicLeadSpeedPlanner.planTargetSpeed(
                LocationUtil.RailType.STRAIGHT,
                0.4,
                true,
                false,
                List.of(LocationUtil.RailType.STRAIGHT, LocationUtil.RailType.CURVE));

        assertEquals(0.35, planned, EPSILON);
        assertEquals(0.4, KinematicSpacingMath.applyTerrainBoost(
                true, LocationUtil.RailType.ASCENDING, 0.2, 0.4), EPSILON);
        assertEquals(0.2, KinematicSpacingMath.applyTerrainBoost(
                false, LocationUtil.RailType.STRAIGHT, 0.2, 0.4), EPSILON);
    }

    @Test
    void interpolatesTrailSamplesWithoutMutatingStoredEndpoints() {
        KinematicTrailBuffer trail = new KinematicTrailBuffer();
        assertTrue(trail.isEmpty());

        trail.addPoint(0, 0, 0, 0.1, 0, 0);
        trail.addPoint(2, 0, 0, 0.2, 0, 0);
        trail.addPoint(4, 0, 0, 0.3, 0, 0);

        KinematicTrailBuffer.TrailPoint sample = trail.samplePointAt(1.0);
        assertFalse(trail.isEmpty());
        assertEquals(3, trail.size());
        assertEquals(3.0, sample.x, EPSILON);
        assertEquals(0.25, sample.vx, EPSILON);
        assertEquals(3.0, sample.cumulativeDistance, EPSILON);
        assertTrue(trail.computeTangent(1.0).getX() > 0.0);
    }
}
