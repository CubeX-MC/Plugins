package org.cubexmc.contract.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContractEventTest {
    @Test
    void toMapRoundTrips() {
        ContractEvent original = new ContractEvent(1_000L, "CREATED", "Alice created");
        ContractEvent restored = ContractEvent.fromMap(original.toMap());
        assertEquals(original, restored);
    }

    @Test
    void fromLegacyLineParsesTimeTypeDetail() {
        ContractEvent event = ContractEvent.fromLegacyLine("1000|ACCEPTED|Bob accepted");
        assertEquals(1000L, event.time());
        assertEquals("ACCEPTED", event.type());
        assertEquals("Bob accepted", event.detail());
    }

    @Test
    void fromLegacyLineHandlesDetailWithPipe() {
        ContractEvent event = ContractEvent.fromLegacyLine("1000|DISPUTED|reason | with | pipes");
        assertEquals("DISPUTED", event.type());
        assertEquals("reason | with | pipes", event.detail());
    }

    @Test
    void fromMapHandlesMissingFields() {
        ContractEvent event = ContractEvent.fromMap(Map.of("type", "X"));
        assertEquals(0L, event.time());
        assertEquals("X", event.type());
        assertEquals("", event.detail());
    }
}
