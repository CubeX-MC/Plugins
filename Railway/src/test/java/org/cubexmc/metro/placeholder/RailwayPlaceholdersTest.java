package org.cubexmc.metro.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.cubexmc.metro.Metro;
import org.cubexmc.metro.service.LineService;
import org.cubexmc.metro.service.LineServiceManager;
import org.junit.jupiter.api.Test;

class RailwayPlaceholdersTest {

    @Test
    void shouldKeepNullAndUnknownRequestsSafe() {
        RailwayPlaceholders placeholders = new RailwayPlaceholders(mock(Metro.class));

        assertEquals("", placeholders.onRequest(null, null));
        assertEquals("", placeholders.onRequest(null, "unknown"));
        assertEquals("railway", placeholders.getIdentifier());
        assertTrue(placeholders.persist());
        assertTrue(placeholders.canRegister());
    }

    @Test
    void shouldFormatHeadwayFallbackEta() {
        Metro plugin = mock(Metro.class);
        LineServiceManager serviceManager = mock(LineServiceManager.class);
        LineService service = mock(LineService.class);
        when(plugin.getLineServiceManager()).thenReturn(serviceManager);
        when(serviceManager.getService("l1")).thenReturn(service);
        when(service.getActiveTrains()).thenReturn(List.of());
        when(serviceManager.estimateNextEtaSeconds("l1", "s3")).thenReturn(125);

        RailwayPlaceholders placeholders = new RailwayPlaceholders(plugin);

        assertEquals("2:05", placeholders.onRequest(null, "eta_l1_s3"));
    }
}
