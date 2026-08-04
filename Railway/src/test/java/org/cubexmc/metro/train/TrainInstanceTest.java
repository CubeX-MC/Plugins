package org.cubexmc.metro.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.UUID;

import org.bukkit.NamespacedKey;
import org.cubexmc.metro.Metro;
import org.cubexmc.metro.control.TrainControlMode;
import org.cubexmc.metro.model.Line;
import org.cubexmc.metro.physics.ReactiveRailPhysics;
import org.cubexmc.metro.service.BlockSectionManager;
import org.cubexmc.metro.service.LineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrainInstanceTest {

    private LineService service;
    private Line line;
    private TrainConsist consist;
    private BlockSectionManager sections;

    @BeforeEach
    void setUp() {
        service = mock(LineService.class);
        line = mock(Line.class);
        consist = new TrainConsist();
        sections = mock(BlockSectionManager.class);

        Metro plugin = mock(Metro.class);
        when(service.getPlugin()).thenReturn(plugin);
        when(service.getTrainKey()).thenReturn(mock(NamespacedKey.class));
        when(service.getBlockSectionManager()).thenReturn(sections);
        when(line.getControlMode()).thenReturn(TrainControlMode.REACTIVE);
    }

    @Test
    void shouldPreserveJavaAccessorsAndClampDwellTicks() {
        TrainInstance train = new TrainInstance(service, line, consist, List.of("alpha", "beta"), 100L, 5);

        assertSame(service, train.getService());
        assertSame(line, train.getLine());
        assertSame(consist, train.getConsist());
        assertTrue(train.getPhysicsEngine() instanceof ReactiveRailPhysics);
        assertTrue(train.isWaiting());
        assertFalse(train.isMoving());
        assertFalse(train.isFinished());
        assertEquals(20, train.getRemainingDwellTicks(100L));
        assertEquals(0, train.getRemainingDwellTicks(120L));
        assertEquals("alpha", train.getCurrentStopId());
        assertNull(train.getTargetStopId());

        UUID virtualId = UUID.randomUUID();
        train.setVirtualTrainId(virtualId);
        assertEquals(virtualId, train.getVirtualTrainId());
        train.prepareForBoarding(null);
    }

    @Test
    void shouldReleaseReservationWhenFinishedImmediately() {
        TrainInstance train = new TrainInstance(service, line, consist, List.of("alpha", "beta"), 0L, 20);
        train.getNavigator().setSectionKey("alpha->beta");

        train.finishImmediately();

        verify(sections).leave("alpha->beta");
        assertNull(train.getNavigator().getSectionKey());
        assertTrue(train.isFinished());
    }

    @Test
    void shouldKeepConstructorFailureContracts() {
        assertThrows(NullPointerException.class,
                () -> new TrainInstance(null, line, consist, List.of("alpha", "beta"), 0L, 20));
        assertThrows(NullPointerException.class,
                () -> new TrainInstance(service, null, consist, List.of("alpha", "beta"), 0L, 20));
        assertThrows(NullPointerException.class,
                () -> new TrainInstance(service, line, null, List.of("alpha", "beta"), 0L, 20));
        assertThrows(NullPointerException.class,
                () -> new TrainInstance(service, line, consist, null, 0L, 20));
        assertThrows(IllegalArgumentException.class,
                () -> new TrainInstance(service, line, consist, List.of("alpha"), 0L, 20));
    }

    @Test
    void shouldPreserveVirtualizationStatePublicFields() throws Exception {
        TrainInstance.VirtualizationState state = new TrainInstance.VirtualizationState(2, 3, 0.75, false);

        assertEquals(2, state.currentIndex);
        assertEquals(3, state.targetIndex);
        assertEquals(0.75, state.progress, 1e-9);
        assertFalse(state.isWaiting);

        for (String fieldName : List.of("currentIndex", "targetIndex", "progress", "isWaiting")) {
            Field field = TrainInstance.VirtualizationState.class.getDeclaredField(fieldName);
            assertTrue(Modifier.isPublic(field.getModifiers()));
            assertTrue(Modifier.isFinal(field.getModifiers()));
        }
    }
}
