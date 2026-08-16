package org.cubexmc.metro.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class PhysicsBridgeMigrationTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void preservesRailPathStaticNullContractAndFallbackCopies() throws Exception {
        Method computeDirection = RailPathUtil.class.getDeclaredMethod(
                "computeDirection", Location.class, Vector.class);
        Method project = RailPathUtil.class.getDeclaredMethod("project", Location.class);
        assertTrue(Modifier.isStatic(computeDirection.getModifiers()));
        assertTrue(Modifier.isStatic(project.getModifiers()));

        Vector fallback = new Vector(0, 0, 2);
        Vector result = RailPathUtil.computeDirection(null, fallback);
        assertEquals(fallback, result);
        assertNotSame(fallback, result);
        assertNull(RailPathUtil.project(null));
    }

    @Test
    void preservesPathSpecSegmentDirectionAndProjection() {
        RailPathUtil.PathSpec path = new RailPathUtil.PathSpec(new double[][] {
                {0.0, 0.0, 0.0},
                {2.0, 0.0, 0.0}
        });
        Block block = mock(Block.class);
        when(block.getX()).thenReturn(10);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(20);
        Location location = new Location(null, 11.0, 65.0, 20.0);

        assertEquals(new Vector(2, 0, 0), path.bestSegmentDirection(location, block));
        Vector projected = path.project(location, block);
        assertEquals(11.0, projected.getX(), EPSILON);
        assertEquals(64.0, projected.getY(), EPSILON);
        assertEquals(20.0, projected.getZ(), EPSILON);
    }

    @Test
    void keepsTrainCartsAbsentAsANormalOptionalIntegrationState() throws Exception {
        PluginManager pluginManager = mock(PluginManager.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

            assertNull(TrainCartsBridge.createIfAvailable());
        }

        Method factory = TrainCartsBridge.class.getDeclaredMethod("createIfAvailable");
        assertTrue(Modifier.isStatic(factory.getModifiers()));
    }

    @Test
    void keepsLeashBridgeClassesExtensible() throws Exception {
        assertFalse(Modifier.isFinal(LeashCoupler.class.getModifiers()));
        assertFalse(Modifier.isFinal(LeashedRailPhysics.class.getModifiers()));
        assertEquals(ReactiveRailPhysics.class, LeashedRailPhysics.class.getSuperclass());
        assertFalse(Modifier.isFinal(LeashCoupler.class.getDeclaredMethod("start").getModifiers()));
        assertFalse(Modifier.isFinal(LeashCoupler.class.getDeclaredMethod("update").getModifiers()));
        assertFalse(Modifier.isFinal(LeashCoupler.class.getDeclaredMethod("cleanup").getModifiers()));
    }
}
