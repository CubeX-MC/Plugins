package org.cubexmc.reputations.api;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class ReputationChangeEventTest {

    @Test
    void exposesJavaSafeChangeSnapshot() {
        UUID playerId = UUID.randomUUID();
        ReputationChangeEvent event = new ReputationChangeEvent(
                playerId, "contract:completed", 2.0, 5.5,
                ReputationChangeEvent.ChangeType.ADD, false);

        assertEquals(playerId, event.playerId());
        assertEquals("contract:completed", event.fieldKey());
        assertEquals(2.0, event.previousValue());
        assertEquals(5.5, event.newValue());
        assertEquals(3.5, event.delta());
        assertEquals(ReputationChangeEvent.ChangeType.ADD, event.changeType());
        assertFalse(event.isAsynchronous());
        assertSame(ReputationChangeEvent.getHandlerList(), event.getHandlers());
    }
}
