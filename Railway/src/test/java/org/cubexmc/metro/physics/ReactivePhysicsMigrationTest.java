package org.cubexmc.metro.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class ReactivePhysicsMigrationTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void preservesSpacingControllerRulesAndStaticBridges() throws Exception {
        assertTrue(Modifier.isStatic(ReactiveSpacingDecisions.class
                .getDeclaredMethod("desiredSpacing", double.class)
                .getModifiers()));
        assertTrue(Modifier.isStatic(ReactiveSpacingDecisions.class
                .getDeclaredMethod("followerTargetAlongSpeed",
                        double.class, double.class, double.class, double.class, double.class)
                .getModifiers()));

        assertEquals(0.5, ReactiveSpacingDecisions.desiredSpacing(0.0), EPSILON);
        assertEquals(0.8, ReactiveSpacingDecisions.desiredSpacing(1.0), EPSILON);
        assertEquals(1.2, ReactiveSpacingDecisions.desiredSpacing(5.0), EPSILON);
        assertEquals(0.4, ReactiveSpacingDecisions.followerTargetAlongSpeed(
                0.4, 0.2, 0.62, 1.0, 0.8), EPSILON);
        assertEquals(0.8, ReactiveSpacingDecisions.followerTargetAlongSpeed(
                0.4, 0.2, 2.0, 1.0, 0.8), EPSILON);
    }

    @Test
    void storesDefensiveCopiesAndPrunesStaleCartState() {
        ReactiveCartStateStore store = new ReactiveCartStateStore();
        UUID retainedId = UUID.randomUUID();
        UUID removedId = UUID.randomUUID();
        Vector velocity = new Vector(0.2, 0.0, 0.4);
        Vector direction = new Vector(0.0, 0.0, 2.0);
        Location position = new Location(null, 1.0, 2.0, 3.0);

        store.rememberVelocity(retainedId, velocity);
        store.rememberDirection(retainedId, direction);
        store.rememberPosition(retainedId, position);
        store.rememberVelocity(removedId, new Vector(1, 0, 0));
        velocity.setX(9.0);
        direction.setZ(9.0);
        position.setX(9.0);

        assertEquals(new Vector(0.2, 0.0, 0.4), store.getVelocity(retainedId));
        assertEquals(new Vector(0.0, 0.0, 1.0), store.getDirection(retainedId));
        assertEquals(1.0, store.getPosition(retainedId).getX(), EPSILON);

        Vector returnedVelocity = store.getVelocity(retainedId);
        returnedVelocity.setX(7.0);
        assertEquals(0.2, store.getVelocity(retainedId).getX(), EPSILON);

        store.retainAll(Set.of(retainedId));
        assertEquals(1, store.size());
        assertNull(store.getVelocity(removedId));

        store.rememberVelocity(null, new Vector());
        store.rememberPosition(retainedId, null);
        assertEquals(1, store.size());
    }

    @Test
    void keepsReactiveEngineExtensibleForJavaBridgeSubclass() {
        assertFalse(Modifier.isFinal(ReactiveRailPhysics.class.getModifiers()));
        assertEquals(ReactiveRailPhysics.class, LeashedRailPhysics.class.getSuperclass());
        assertTrue(TrainPhysicsEngine.class.isAssignableFrom(ReactiveRailPhysics.class));
    }
}
