package org.cubexmc.metro.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.cubexmc.metro.Metro;
import org.cubexmc.metro.service.strategy.GlobalDispatchStrategy;
import org.junit.jupiter.api.Test;

class LineServiceSchedulingTest {

    @Test
    void shouldPreserveHeadwaySchedulingAndTrainCarClamp() {
        LineService service = new LineService(
                mock(Metro.class),
                mock(LineServiceManager.class),
                "line-a",
                10,
                40,
                0,
                mock(DispatchStrategy.class));

        assertEquals(1, service.getTrainCars());
        assertEquals(10, service.estimateNextEtaSeconds(0L));
        assertEquals(8, service.estimateNextEtaSeconds(40L));

        assertTrue(service.tryMarkDeparture(100L));
        assertFalse(service.tryMarkDeparture(120L));
        assertEquals(9, service.estimateNextEtaSeconds(120L));

        service.setTrainCars(-4);
        assertEquals(1, service.getTrainCars());
    }

    @Test
    void globalDispatchShouldOnlySpawnInsideDepartureWindow() {
        LineService service = mock(LineService.class);
        GlobalDispatchStrategy strategy = new GlobalDispatchStrategy();
        when(service.isDepartureWindow(200L)).thenReturn(false);

        strategy.tick(service, 200L);

        verify(service, never()).markDeparture(200L);
        verify(service, never()).spawnTrain(200L);

        when(service.isDepartureWindow(220L)).thenReturn(true);
        when(service.isLoopLine()).thenReturn(false);
        strategy.tick(service, 220L);

        verify(service).markDeparture(220L);
        verify(service).spawnTrain(220L);
    }
}
