package org.cubexmc.metro.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ArrivalHeuristicsTest {

    @Test
    void shouldUseRailwayOldFixedTwoBlockArrivalThreshold() {
        assertEquals(2.0, ArrivalHeuristics.thresholdBlocks(0.0));
        assertEquals(2.0, ArrivalHeuristics.thresholdBlocks(1.2));
        assertTrue(ArrivalHeuristics.shouldArrive(4.0, 0.4));
        assertFalse(ArrivalHeuristics.shouldArrive(9.0, 0.4));
    }
}
