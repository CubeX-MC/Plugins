package org.cubexmc.metro.service.virtual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.cubexmc.metro.estimation.TravelTimeEstimator;
import org.cubexmc.metro.model.Line;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VirtualTrainPoolTest {

    @TempDir
    File tempDir;

    @Test
    void shouldDistributeInitialTrainsAcrossDwellAndTravelEvents() {
        Line line = new Line("red", "Red");
        line.addStop("A", -1);
        line.addStop("B", -1);
        line.addStop("C", -1);
        TravelTimeEstimator estimator = new TravelTimeEstimator(new FixedSettings(),
                tempDir, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        VirtualTrainPool pool = new VirtualTrainPool("red", 100);
        pool.initialize(line, 20, estimator, 1_000L);

        List<VirtualTrain> trains = pool.getVirtualTrains();
        assertFalse(trains.isEmpty());
        assertTrue(trains.stream().anyMatch(train ->
                train.getLastEventType() == VirtualTrain.EventType.ARRIVAL
                        && train.getNextEventTick() > 1_000L));
        assertTrue(trains.stream().anyMatch(train ->
                train.getLastEventType() == VirtualTrain.EventType.DEPARTURE
                        && train.getNextEventTick() > 1_001L));
    }

    @Test
    void shouldPauseAndResumeScheduledEventsForMaterializedTrain() {
        Line line = new Line("red", "Red");
        line.addStop("A", -1);
        line.addStop("B", -1);
        TravelTimeEstimator estimator = estimator();
        VirtualTrainPool pool = new VirtualTrainPool("red", 100);
        pool.initialize(line, 1_000, estimator, 1_000L);

        VirtualTrain train = pool.getVirtualTrains().get(0);
        long departureTick = train.getNextEventTick();
        pool.markMaterialized(train.getId());
        pool.tick(departureTick, estimator);

        assertEquals(VirtualTrain.EventType.ARRIVAL, train.getLastEventType());
        pool.releaseMaterialized(train.getId());
        pool.tick(departureTick, estimator);

        assertEquals(VirtualTrain.EventType.DEPARTURE, train.getLastEventType());
        assertEquals(1, train.getTargetStopIndex());
    }

    @Test
    void shouldReturnDefensiveTrainListCopy() {
        Line line = new Line("red", "Red");
        line.addStop("A", -1);
        line.addStop("B", -1);
        VirtualTrainPool pool = new VirtualTrainPool("red", 100);
        pool.initialize(line, 1_000, estimator(), 1_000L);

        List<VirtualTrain> snapshot = pool.getVirtualTrains();
        snapshot.clear();

        assertFalse(pool.getVirtualTrains().isEmpty());
    }

    @Test
    void shouldKeepJavaNullGuardForMockedLineTopology() {
        Line line = mock(Line.class);
        when(line.getOrderedStopIds()).thenReturn(null);
        VirtualTrainPool pool = new VirtualTrainPool("red", 100);

        pool.initialize(line, 20, estimator(), 1_000L);

        assertTrue(pool.getVirtualTrains().isEmpty());
    }

    @Test
    void shouldPreserveMovingProgressFromElapsedTicks() {
        VirtualTrain train = new VirtualTrain("red", List.of("A", "B"), 100, 0, 0.0, 1_000L);
        train.initializeMovingBetween(0, 1, 0.5, 20.0, 1_000L);

        assertEquals(0.5, train.getSegmentProgress(1_000L));
        assertEquals(1_200L, train.getNextEventTick());
    }

    private TravelTimeEstimator estimator() {
        return new TravelTimeEstimator(new FixedSettings(), tempDir,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    private static final class FixedSettings implements TravelTimeEstimator.Settings {
        @Override
        public boolean enabled() { return false; }

        @Override
        public double defaultSectionSeconds() { return 30.0; }

        @Override
        public double priorStrength() { return 3.0; }

        @Override
        public double outlierSigma() { return 4.0; }

        @Override
        public double decayPerDay() { return 0.0; }
    }
}
