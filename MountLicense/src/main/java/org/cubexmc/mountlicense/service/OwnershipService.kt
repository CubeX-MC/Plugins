package org.cubexmc.mountlicense.service

import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.mountlicense.persistence.VehicleIndex
import java.util.UUID

class OwnershipService(
    private val keys: PdcKeys,
    private val index: VehicleIndex,
) {
    fun readOwner(entity: Entity?): UUID? {
        if (entity == null) return null
        val raw = entity.persistentDataContainer.get(keys.ownerUuid(), PersistentDataType.STRING) ?: return null
        return try {
            UUID.fromString(raw)
        } catch (ex: IllegalArgumentException) {
            null
        }
    }

    fun readVehicleId(entity: Entity?): UUID? {
        if (entity == null) return null
        val raw = entity.persistentDataContainer.get(keys.vehicleId(), PersistentDataType.STRING) ?: return null
        return try {
            UUID.fromString(raw)
        } catch (ex: IllegalArgumentException) {
            null
        }
    }

    fun isRegistered(entity: Entity?): Boolean = readVehicleId(entity) != null

    fun readProfileId(entity: Entity?): String? {
        if (entity == null) return null
        return entity.persistentDataContainer.get(keys.profile(), PersistentDataType.STRING)
    }

    fun isOwner(entity: Entity?, playerUuid: UUID?): Boolean {
        val owner = readOwner(entity)
        return owner != null && owner == playerUuid
    }

    fun isTrustee(entity: Entity?, playerUuid: UUID?): Boolean {
        if (playerUuid == null) return false
        val vehicleId = readVehicleId(entity) ?: return false
        val record = index.byId(vehicleId)
        return record != null && record.isTrustee(playerUuid)
    }

    fun isOwnerOrTrustee(entity: Entity?, playerUuid: UUID?): Boolean =
        isOwner(entity, playerUuid) || isTrustee(entity, playerUuid)

    fun canManage(player: Player?, entity: Entity?): Boolean {
        if (player == null || entity == null) return false
        if (!isRegistered(entity)) return true
        return isOwner(entity, player.uniqueId) || player.hasPermission(BYPASS_PERMISSION)
    }

    fun canAccess(player: Player?, entity: Entity?): Boolean {
        if (player == null || entity == null) return false
        if (!isRegistered(entity)) return true
        val playerUuid = player.uniqueId
        if (isOwner(entity, playerUuid)) return true
        if (isTrustee(entity, playerUuid)) return true
        return player.hasPermission(BYPASS_PERMISSION)
    }

    companion object {
        const val BYPASS_PERMISSION: String = "mountlicense.admin.bypass"
    }
}
