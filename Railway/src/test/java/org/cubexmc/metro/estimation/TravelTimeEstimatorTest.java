package org.cubexmc.metro.estimation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TravelTimeEstimatorTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    @TempDir
    File tempDir;

    @Test
    void shouldPersistAndReloadWeightedEstimate() {
        TravelTimeEstimator estimator = new TravelTimeEstimator(new FixedSettings(), tempDir, FIXED_CLOCK);
        estimator.record("red", "a", "b", 60.0, 1.0);

        assertEquals(37.5, estimator.estimateSeconds("red", "a", "b"), 1.0e-9);
        estimator.save();

        TravelTimeEstimator reloaded = new TravelTimeEstimator(new FixedSettings(), tempDir, FIXED_CLOCK);
        reloaded.load();
        assertEquals(37.5, reloaded.estimateSeconds("red", "a", "b"), 1.0e-9);
    }

    @Test
    void shouldIgnoreInvalidDurationsAndWeights() {
        TravelTimeEstimator estimator = new TravelTimeEstimator(new FixedSettings(), tempDir, FIXED_CLOCK);

        estimator.record("red", "a", "b", 0.01, 1.0);
        estimator.record("red", "a", "b", 601.0, 1.0);
        estimator.record("red", "a", "b", 60.0, 0.0);

        assertEquals(30.0, estimator.estimateSeconds("red", "a", "b"), 1.0e-9);
    }

    private static final class FixedSettings implements TravelTimeEstimator.Settings {
        @Override
        public boolean enabled() { return true; }

        @Override
        public double defaultSectionSeconds() { return 30.0; }

        @Override
        public double priorStrength() { return 3.0; }

        @Override
        public double outlierSigma() { return 0.0; }

        @Override
        public double decayPerDay() { return 1.0; }
    }
}
