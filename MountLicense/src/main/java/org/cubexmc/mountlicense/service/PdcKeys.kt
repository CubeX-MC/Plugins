package org.cubexmc.mountlicense.service

import org.bukkit.NamespacedKey
import org.cubexmc.mountlicense.MountLicensePlugin

class PdcKeys constructor(
    itemRoleKey: NamespacedKey,
    vehicleIdKey: NamespacedKey,
    ownerUuidKey: NamespacedKey,
    profileKey: NamespacedKey,
    stateKey: NamespacedKey,
    createdAtKey: NamespacedKey,
    schemaVersionKey: NamespacedKey,
    keyBoundVehicleKey: NamespacedKey,
) {
    constructor(plugin: MountLicensePlugin) : this(
        NamespacedKey(plugin, "item_role"),
        NamespacedKey(plugin, "vehicle_id"),
        NamespacedKey(plugin, "owner_uuid"),
        NamespacedKey(plugin, "profile"),
        NamespacedKey(plugin, "state"),
        NamespacedKey(plugin, "created_at"),
        NamespacedKey(plugin, "schema_version"),
        NamespacedKey(plugin, "key_bound_vehicle"),
    )

    private val itemRoleKey: NamespacedKey = itemRoleKey
    private val vehicleIdKey: NamespacedKey = vehicleIdKey
    private val ownerUuidKey: NamespacedKey = ownerUuidKey
    private val profileKey: NamespacedKey = profileKey
    private val stateKey: NamespacedKey = stateKey
    private val createdAtKey: NamespacedKey = createdAtKey
    private val schemaVersionKey: NamespacedKey = schemaVersionKey
    private val keyBoundVehicleKey: NamespacedKey = keyBoundVehicleKey

    fun itemRole(): NamespacedKey = itemRoleKey

    fun vehicleId(): NamespacedKey = vehicleIdKey

    fun ownerUuid(): NamespacedKey = ownerUuidKey

    fun profile(): NamespacedKey = profileKey

    fun state(): NamespacedKey = stateKey

    fun createdAt(): NamespacedKey = createdAtKey

    fun schemaVersion(): NamespacedKey = schemaVersionKey

    fun keyBoundVehicle(): NamespacedKey = keyBoundVehicleKey

    companion object {
        const val ITEM_ROLE_LICENSE: String = "license"
        const val ITEM_ROLE_KEY: String = "key"
        const val ITEM_ROLE_STATION_PERMIT: String = "station_permit"
        const val SCHEMA_VERSION: Int = 1
    }
}
