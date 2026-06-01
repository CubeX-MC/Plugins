package org.cubexmc.mountlicense.service;

import java.util.UUID;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.cubexmc.mountlicense.model.VehicleRecord;
import org.cubexmc.mountlicense.persistence.VehicleIndex;

public class OwnershipService {

    public static final String BYPASS_PERMISSION = "mountlicense.admin.bypass";

    private final PdcKeys keys;
    private final VehicleIndex index;

    public OwnershipService(PdcKeys keys, VehicleIndex index) {
        this.keys = keys;
        this.index = index;
    }

    public UUID readOwner(Entity entity) {
        if (entity == null) return null;
        String raw = entity.getPersistentDataContainer()
                .get(keys.ownerUuid(), PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public UUID readVehicleId(Entity entity) {
        if (entity == null) return null;
        String raw = entity.getPersistentDataContainer()
                .get(keys.vehicleId(), PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public boolean isRegistered(Entity entity) {
        return readVehicleId(entity) != null;
    }

    public String readProfileId(Entity entity) {
        if (entity == null) return null;
        return entity.getPersistentDataContainer().get(keys.profile(), PersistentDataType.STRING);
    }

    public boolean isOwner(Entity entity, UUID playerUuid) {
        UUID owner = readOwner(entity);
        return owner != null && owner.equals(playerUuid);
    }

    public boolean isTrustee(Entity entity, UUID playerUuid) {
        if (playerUuid == null) return false;
        UUID vehicleId = readVehicleId(entity);
        if (vehicleId == null) return false;
        VehicleRecord record = index.byId(vehicleId);
        return record != null && record.isTrustee(playerUuid);
    }

    public boolean isOwnerOrTrustee(Entity entity, UUID playerUuid) {
        return isOwner(entity, playerUuid) || isTrustee(entity, playerUuid);
    }

    public boolean canManage(Player player, Entity entity) {
        if (player == null || entity == null) return false;
        if (!isRegistered(entity)) return true;
        return isOwner(entity, player.getUniqueId()) || player.hasPermission(BYPASS_PERMISSION);
    }

    public boolean canAccess(Player player, Entity entity) {
        if (player == null || entity == null) return false;
        if (!isRegistered(entity)) return true;
        UUID playerUuid = player.getUniqueId();
        if (isOwner(entity, playerUuid)) return true;
        if (isTrustee(entity, playerUuid)) return true;
        return player.hasPermission(BYPASS_PERMISSION);
    }
}
