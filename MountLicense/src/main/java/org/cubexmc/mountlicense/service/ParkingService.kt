package org.cubexmc.mountlicense.service

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.mountlicense.MountLicensePlugin
import org.cubexmc.mountlicense.lang.LanguageManager
import org.cubexmc.mountlicense.model.VehicleRecord
import org.cubexmc.mountlicense.model.VehicleState
import org.cubexmc.mountlicense.persistence.VehicleIndex
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ParkingService(
    private val plugin: MountLicensePlugin,
    private val keys: PdcKeys,
    private val index: VehicleIndex,
    private val ownership: OwnershipService,
    @Suppress("unused") private val lang: LanguageManager,
) {
    private val pendingReleases: MutableMap<UUID, PendingRelease> = ConcurrentHashMap()

    enum class ActionResult {
        SUCCESS,
        NOT_REGISTERED,
        NOT_OWNER,
        ALREADY_IN_STATE,
        CONFIRM_REQUIRED,
        CONFIRMED,
    }

    fun park(player: Player, entity: Entity?): ActionResult = transitionState(player, entity, VehicleState.PARKED, true)

    fun unpark(player: Player, entity: Entity?): ActionResult = transitionState(player, entity, VehicleState.ACTIVE, true)

    fun lock(player: Player, entity: Entity?): ActionResult = transitionState(player, entity, VehicleState.LOCKED, false)

    fun unlock(player: Player, entity: Entity?): ActionResult = transitionState(player, entity, VehicleState.ACTIVE, false)

    fun release(player: Player, entity: Entity?): ActionResult {
        if (entity == null || !ownership.isRegistered(entity)) return ActionResult.NOT_REGISTERED
        if (!ownership.canManage(player, entity)) return ActionResult.NOT_OWNER

        val vehicleId = ownership.readVehicleId(entity)
        val now = System.currentTimeMillis()
        val pending = pendingReleases[player.uniqueId]
        if (pending != null && pending.vehicleId == vehicleId && now - pending.requestedAt < CONFIRM_WINDOW_MS) {
            pendingReleases.remove(player.uniqueId)
            doRelease(entity, vehicleId)
            return ActionResult.CONFIRMED
        }

        pendingReleases[player.uniqueId] = PendingRelease(vehicleId, now)
        return ActionResult.CONFIRM_REQUIRED
    }

    private fun doRelease(entity: Entity, vehicleId: UUID?) {
        entity.persistentDataContainer.remove(keys.vehicleId())
        entity.persistentDataContainer.remove(keys.ownerUuid())
        entity.persistentDataContainer.remove(keys.profile())
        entity.persistentDataContainer.remove(keys.state())
        entity.persistentDataContainer.remove(keys.createdAt())
        entity.removeScoreboardTag("mountlicense_registered")
        entity.customName = null
        if (entity is LivingEntity) {
            entity.setAI(true)
            entity.removeWhenFarAway = true
        }
        if (vehicleId != null) {
            index.remove(vehicleId)
        }
    }

    private fun transitionState(player: Player, entity: Entity?, target: VehicleState, adjustAi: Boolean): ActionResult {
        if (entity == null || !ownership.isRegistered(entity)) return ActionResult.NOT_REGISTERED
        if (!ownership.canManage(player, entity)) return ActionResult.NOT_OWNER

        val vehicleId = ownership.readVehicleId(entity)
        val record = if (vehicleId == null) null else index.byId(vehicleId)
        if (record != null && record.state() == target) return ActionResult.ALREADY_IN_STATE

        entity.persistentDataContainer.set(keys.state(), PersistentDataType.STRING, target.name)
        if (record != null) {
            record.setState(target)
            val loc: Location = entity.location
            if (loc.world != null) {
                record.setLocation(loc.world?.name, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
            }
            record.setLastSeenAt(System.currentTimeMillis())
            index.markDirty()
        }

        if (adjustAi && entity is LivingEntity && entity !is Player) {
            entity.setAI(target == VehicleState.ACTIVE)
        }
        return ActionResult.SUCCESS
    }

    fun placeholdersFor(record: VehicleRecord?, entity: Entity?): Map<String, String> {
        val p = HashMap<String, String>()
        if (record != null) {
            p["vehicle_id"] = record.shortId()
            record.profile()?.let { p["profile"] = it }
            p["state"] = record.state().name
        } else if (entity != null) {
            p["entity_type"] = entity.type.name
        }
        return p
    }

    fun hasPendingRelease(playerUuid: UUID): Boolean {
        val p = pendingReleases[playerUuid]
        return p != null && System.currentTimeMillis() - p.requestedAt < CONFIRM_WINDOW_MS
    }

    fun cancelPending(playerUuid: UUID) {
        pendingReleases.remove(playerUuid)
    }

    /** Convenience: find what the player is looking at, if any. */
    fun findTargeted(player: Player, maxDistance: Double): Entity? {
        val ray = player.world.rayTraceEntities(
            player.eyeLocation,
            player.eyeLocation.direction,
            maxDistance,
        ) { e -> e !== player }
        return ray?.hitEntity
    }

    fun findEntityForVehicle(record: VehicleRecord?): Entity? {
        if (record == null) return null
        return Bukkit.getEntity(record.entityUuid())
    }

    private class PendingRelease(
        val vehicleId: UUID?,
        val requestedAt: Long,
    )

    private companion object {
        const val CONFIRM_WINDOW_MS: Long = 30_000L
    }
}
