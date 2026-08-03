package org.cubexmc.metro.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.cubexmc.metro.manager.StopManager;
import org.cubexmc.metro.model.Stop;
import org.cubexmc.metro.physics.TrainPhysicsEngine;
import org.cubexmc.metro.service.BlockSectionManager;
import org.cubexmc.metro.service.LineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrainNavigatorTest {

    private TrainInstance train;
    private LineService service;
    private StopManager stopManager;
    private BlockSectionManager sections;
    private TrainPhysicsEngine physicsEngine;

    @BeforeEach
    void setUp() {
        train = mock(TrainInstance.class);
        service = mock(LineService.class);
        stopManager = mock(StopManager.class);
        sections = mock(BlockSectionManager.class);
        physicsEngine = mock(TrainPhysicsEngine.class);

        when(train.getService()).thenReturn(service);
        when(train.getPhysicsEngine()).thenReturn(physicsEngine);
        when(service.getStopManager()).thenReturn(stopManager);
        when(service.getBlockSectionManager()).thenReturn(sections);
    }

    @Test
    void shouldDepartAndPreserveJavaNavigationAccessors() {
        Stop fromStop = new Stop("alpha", "Alpha");
        fromStop.setLaunchYaw(0.0f);
        Stop toStop = new Stop("beta", "Beta");
        toStop.setStopPointLocation(new Location(null, 10.0, 64.0, 10.0));
        when(stopManager.getStop("alpha")).thenReturn(fromStop);
        when(stopManager.getStop("beta")).thenReturn(toStop);
        when(service.buildSectionKey("alpha", "beta")).thenReturn("alpha->beta");
        when(sections.tryEnter("alpha->beta")).thenReturn(true);

        List<String> stopIds = List.of("alpha", "beta");
        TrainNavigator navigator = new TrainNavigator(train, stopIds);
        navigator.attemptDeparture(42L);

        assertEquals(0, navigator.getCurrentIndex());
        assertEquals(1, navigator.getTargetIndex());
        assertEquals("beta", navigator.getTargetStopId());
        assertEquals("alpha->beta", navigator.getSectionKey());
        assertSame(stopIds, navigator.getStopIds());
        Vector direction = navigator.getTravelDirection();
        assertEquals(0.0, direction.getX(), 1e-9);
        assertEquals(1.0, direction.getZ(), 1e-9);

        verify(train).applyInitialBoost(direction);
        verify(physicsEngine).onDeparture(train, fromStop);
        verify(train).publishDepartureEvents(fromStop, toStop);
        verify(train).setState(TrainInstance.TrainState.MOVING, 42L);
        verify(train).setReadyToDepart(false);
        verify(train).onSegmentStart(42L);
    }

    @Test
    void shouldReleaseSectionAndApplyTerminalArrival() {
        Stop stop = new Stop("beta", "Beta");
        TrainConsist consist = mock(TrainConsist.class);
        when(train.isMoving()).thenReturn(true);
        when(train.getConsist()).thenReturn(consist);
        when(service.isLoopLine()).thenReturn(false);

        TrainNavigator navigator = new TrainNavigator(train, List.of("alpha", "beta"));
        navigator.setTargetIndex(1);
        navigator.setSectionKey("alpha->beta");
        navigator.setTravelDirection(new Vector(0, 0, 1));

        navigator.handleArrival(stop, 73L);

        verify(consist).zeroVelocity();
        verify(sections).leave("alpha->beta");
        verify(train).setState(TrainInstance.TrainState.TERMINATING, 73L);
        verify(train).setReadyToDepart(false);
        verify(physicsEngine).onArrival(train, stop, 73L);
        verify(train).publishArrivalEvents(stop, true);
        verify(train).tryRecordTravelTimeSample(73L);
        assertEquals(1, navigator.getCurrentIndex());
        assertEquals(-1, navigator.getTargetIndex());
        assertNull(navigator.getSectionKey());
        assertNull(navigator.getTravelDirection());
    }

    @Test
    void shouldTerminateAndCleanupInvalidDepartureState() {
        TrainNavigator navigator = new TrainNavigator(train, List.of("alpha"));
        navigator.setSectionKey("reserved");
        navigator.setTravelDirection(new Vector(1, 0, 0));

        navigator.attemptDeparture(9L);

        verify(sections).leave("reserved");
        verify(train).setState(TrainInstance.TrainState.TERMINATING, 9L);
        assertNull(navigator.getSectionKey());
        assertNull(navigator.getTravelDirection());
        assertNull(navigator.getTargetStopId());

        navigator.cleanup();
        assertFalse(navigator.getStopIds().isEmpty());
        assertTrue(navigator.getTargetIndex() < 0);
    }
}
