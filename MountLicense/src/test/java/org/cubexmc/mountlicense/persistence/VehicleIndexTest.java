package org.cubexmc.mountlicense.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import org.cubexmc.mountlicense.MountLicensePlugin;
import org.cubexmc.mountlicense.config.ConfigManager;
import org.cubexmc.mountlicense.model.VehicleRecord;
import org.cubexmc.mountlicense.model.VehicleState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VehicleIndexTest {

    @TempDir
    Path tempDir;

    @Test
    void flushAndLoadPreserveRecordFieldsAndTrustees() {
        MountLicensePlugin plugin = mockPlugin(tempDir);
        File file = tempDir.resolve("vehicles.yml").toFile();

        UUID vehicleId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID trusteeId = UUID.randomUUID();

        VehicleRecord record = new VehicleRecord(vehicleId, entityId, "HORSE", ownerId, "horse");
        record.setPlate("ABC-123");
        record.setDisplayName("Angus's horse");
        record.setState(VehicleState.PARKED);
        record.setLocation("world", 10.5, 64.0, -2.25, 90.0f, 0.0f);
        record.setStationId("spawn");
        record.setCreatedAt(123L);
        record.setLastSeenAt(456L);
        record.addTrustee(trusteeId);

        VehicleIndex index = new VehicleIndex(plugin, file);
        index.put(record);
        index.flush();

        VehicleIndex reloaded = new VehicleIndex(plugin, file);
        reloaded.load();

        VehicleRecord loaded = reloaded.byId(vehicleId);
        assertNotNull(loaded);
        assertEquals(entityId, loaded.entityUuid());
        assertEquals(ownerId, loaded.ownerUuid());
        assertEquals("horse", loaded.profile());
        assertEquals("ABC-123", loaded.plate());
        assertEquals("ABC-123", loaded.shortId());
        assertEquals(VehicleState.PARKED, loaded.state());
        assertEquals("spawn", loaded.stationId());
        assertEquals("world", loaded.world());
        assertEquals(10.5, loaded.x());
        assertEquals(64.0, loaded.y());
        assertEquals(-2.25, loaded.z());
        assertEquals(123L, loaded.createdAt());
        assertEquals(456L, loaded.lastSeenAt());
        assertEquals(1, loaded.trustees().size());
        assertEquals(1, reloaded.byOwner(ownerId).size());
        assertEquals(vehicleId, reloaded.byEntity(entityId).vehicleId());
    }

    private static MountLicensePlugin mockPlugin(Path dataFolder) {
        MountLicensePlugin plugin = mock(MountLicensePlugin.class);
        ConfigManager config = mock(ConfigManager.class);
        when(plugin.configManager()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("VehicleIndexTest"));
        when(config.getAutosaveIntervalTicks()).thenReturn(0);
        return plugin;
    }
}
