package org.cubexmc.mountlicense.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.cubexmc.mountlicense.model.VehicleRecord;
import org.cubexmc.mountlicense.persistence.VehicleIndex;
import org.junit.jupiter.api.Test;

class OwnershipServiceTest {

    @Test
    void accessAllowsOwnerTrusteeAndBypassOnly() {
        UUID vehicleId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID trusteeId = UUID.randomUUID();
        UUID bystanderId = UUID.randomUUID();

        PdcKeys keys = testKeys();
        VehicleRecord record = new VehicleRecord(vehicleId, entityId, "HORSE", ownerId, "horse");
        record.addTrustee(trusteeId);

        VehicleIndex index = mock(VehicleIndex.class);
        when(index.byId(vehicleId)).thenReturn(record);

        Entity entity = mockRegisteredEntity(keys, vehicleId, ownerId);
        OwnershipService ownership = new OwnershipService(keys, index);

        assertTrue(ownership.canAccess(player(ownerId, false), entity));
        assertTrue(ownership.canAccess(player(trusteeId, false), entity));
        assertFalse(ownership.canAccess(player(bystanderId, false), entity));
        assertTrue(ownership.canAccess(player(bystanderId, true), entity));
    }

    private static Player player(UUID uuid, boolean bypass) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.hasPermission(OwnershipService.BYPASS_PERMISSION)).thenReturn(bypass);
        return player;
    }

    private static Entity mockRegisteredEntity(PdcKeys keys, UUID vehicleId, UUID ownerId) {
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(pdc.get(keys.vehicleId(), PersistentDataType.STRING)).thenReturn(vehicleId.toString());
        when(pdc.get(keys.ownerUuid(), PersistentDataType.STRING)).thenReturn(ownerId.toString());

        Entity entity = mock(Entity.class);
        when(entity.getPersistentDataContainer()).thenReturn(pdc);
        return entity;
    }

    private static PdcKeys testKeys() {
        return new PdcKeys(
                key("item_role"),
                key("vehicle_id"),
                key("owner_uuid"),
                key("profile"),
                key("state"),
                key("created_at"),
                key("schema_version"),
                key("key_bound_vehicle")
        );
    }

    private static NamespacedKey key(String name) {
        return new NamespacedKey("mountlicense", name);
    }
}
