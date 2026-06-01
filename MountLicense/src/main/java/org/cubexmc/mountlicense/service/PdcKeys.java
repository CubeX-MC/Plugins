package org.cubexmc.mountlicense.service;

import org.bukkit.NamespacedKey;
import org.cubexmc.mountlicense.MountLicensePlugin;

public final class PdcKeys {

    private final NamespacedKey itemRoleKey;
    private final NamespacedKey vehicleIdKey;
    private final NamespacedKey ownerUuidKey;
    private final NamespacedKey profileKey;
    private final NamespacedKey stateKey;
    private final NamespacedKey createdAtKey;
    private final NamespacedKey schemaVersionKey;
    private final NamespacedKey keyBoundVehicleKey;

    public PdcKeys(MountLicensePlugin plugin) {
        this(
                new NamespacedKey(plugin, "item_role"),
                new NamespacedKey(plugin, "vehicle_id"),
                new NamespacedKey(plugin, "owner_uuid"),
                new NamespacedKey(plugin, "profile"),
                new NamespacedKey(plugin, "state"),
                new NamespacedKey(plugin, "created_at"),
                new NamespacedKey(plugin, "schema_version"),
                new NamespacedKey(plugin, "key_bound_vehicle")
        );
    }

    PdcKeys(NamespacedKey itemRoleKey, NamespacedKey vehicleIdKey, NamespacedKey ownerUuidKey,
            NamespacedKey profileKey, NamespacedKey stateKey, NamespacedKey createdAtKey,
            NamespacedKey schemaVersionKey, NamespacedKey keyBoundVehicleKey) {
        this.itemRoleKey = itemRoleKey;
        this.vehicleIdKey = vehicleIdKey;
        this.ownerUuidKey = ownerUuidKey;
        this.profileKey = profileKey;
        this.stateKey = stateKey;
        this.createdAtKey = createdAtKey;
        this.schemaVersionKey = schemaVersionKey;
        this.keyBoundVehicleKey = keyBoundVehicleKey;
    }

    public NamespacedKey itemRole() { return itemRoleKey; }
    public NamespacedKey vehicleId() { return vehicleIdKey; }
    public NamespacedKey ownerUuid() { return ownerUuidKey; }
    public NamespacedKey profile() { return profileKey; }
    public NamespacedKey state() { return stateKey; }
    public NamespacedKey createdAt() { return createdAtKey; }
    public NamespacedKey schemaVersion() { return schemaVersionKey; }
    public NamespacedKey keyBoundVehicle() { return keyBoundVehicleKey; }

    public static final String ITEM_ROLE_LICENSE = "license";
    public static final String ITEM_ROLE_KEY = "key";
    public static final String ITEM_ROLE_STATION_PERMIT = "station_permit";
    public static final int SCHEMA_VERSION = 1;
}
