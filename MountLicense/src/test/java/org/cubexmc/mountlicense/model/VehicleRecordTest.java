package org.cubexmc.mountlicense.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class VehicleRecordTest {

    @Test
    void missingPlateFallsBackToStableShortPlate() {
        UUID vehicleId = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        VehicleRecord record = new VehicleRecord(
                vehicleId,
                UUID.randomUUID(),
                "HORSE",
                UUID.randomUUID(),
                "horse"
        );

        assertEquals("123-456", record.shortId());

        record.setPlate("abc-123");

        assertEquals("ABC-123", record.plate());
        assertEquals("ABC-123", record.shortId());
    }
}
