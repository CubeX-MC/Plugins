package org.cubexmc.mountlicense.service

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.mountlicense.MountLicensePlugin
import org.cubexmc.mountlicense.config.ProfileRegistry
import org.cubexmc.mountlicense.lang.LanguageManager
import org.cubexmc.mountlicense.model.VehicleFeature
import org.cubexmc.mountlicense.model.VehicleRecord
import org.cubexmc.mountlicense.model.VehicleState
import org.cubexmc.mountlicense.persistence.VehicleIndex
import org.cubexmc.mountlicense.util.CooldownTracker
import java.util.UUID

class RecallService(
    private val plugin: MountLicensePlugin,
    private val ownership: OwnershipService,
    private val profiles: ProfileRegistry,
    private val index: VehicleIndex,
    private val lang: LanguageManager,
) {
    private val cooldowns = CooldownTracker()

    enum class Result {
        SUCCESS,
        NOT_FOUND,
        NOT_OWNER,
        ENTITY_NOT_LOADED,
        NO_NEARBY_OWNED,
        PROFILE_NO_SUMMON,
        UNSAFE_DESTINATION,
        COOLDOWN,
        NO_PERMISSION,
    }

    fun recallById(player: Player, vehicleId: UUID): Result {
        if (!player.hasPermission(KEY_USE_PERMISSION)) return Result.NO_PERMISSION
        val record = index.byId(vehicleId) ?: return Result.NOT_FOUND
        if (!matchesOwner(player, record)) return Result.NOT_OWNER

        val profile = profiles.byId(record.profile())
        if (profile != null && !profile.has(VehicleFeature.SUMMON)) {
            return Result.PROFILE_NO_SUMMON
        }

        val entity = Bukkit.getEntity(record.entityUuid()) ?: return Result.ENTITY_NOT_LOADED
        return recallEntity(player, entity, record)
    }

    fun recallNearest(player: Player): Result {
        if (!player.hasPermission(KEY_USE_PERMISSION)) return Result.NO_PERMISSION
        val nearest = findNearestOwnedLoaded(player) ?: return Result.NO_NEARBY_OWNED

        val vehicleId = ownership.readVehicleId(nearest) ?: return Result.NOT_FOUND
        val record = index.byId(vehicleId) ?: return Result.NOT_FOUND
        return recallEntity(player, nearest, record)
    }

    fun findNearestOwnedLoaded(player: Player): Entity? {
        val radius = plugin.configManager().getRecallSearchRadius()
        val playerUuid = player.uniqueId

        var best: Entity? = null
        var bestDistSq = radius * radius
        val origin = player.location

        for (e in player.getNearbyEntities(radius, radius, radius)) {
            if (!ownership.isOwner(e, playerUuid)) continue
            val profileId = ownership.readProfileId(e)
            val profile = profiles.byId(profileId)
            if (profile != null && !profile.has(VehicleFeature.SUMMON)) continue

            val dSq = e.location.distanceSquared(origin)
            if (dSq < bestDistSq) {
                bestDistSq = dSq
                best = e
            }
        }
        return best
    }

    private fun recallEntity(player: Player, entity: Entity, record: VehicleRecord): Result {
        if (!cooldowns.tryAcquire(player.uniqueId, plugin.configManager().getRecallCooldownSeconds())) {
            return Result.COOLDOWN
        }

        val destination = player.location
        if (plugin.configManager().isRecallRequireSafeDestination() && !isSafeDestination(destination)) {
            cooldowns.clear(player.uniqueId)
            return Result.UNSAFE_DESTINATION
        }

        entity.teleport(destination)

        if (plugin.configManager().isRecallWakeOnRecall()) {
            if (entity is LivingEntity && entity !is Player) {
                entity.setAI(true)
            }
            record.setState(VehicleState.ACTIVE)
            entity.persistentDataContainer.set(
                plugin.pdcKeys().state(),
                PersistentDataType.STRING,
                VehicleState.ACTIVE.name,
            )
        }

        if (destination.world != null) {
            record.setLocation(
                destination.world?.name,
                destination.x,
                destination.y,
                destination.z,
                destination.yaw,
                destination.pitch,
            )
        }
        record.setLastSeenAt(System.currentTimeMillis())
        index.markDirty()
        return Result.SUCCESS
    }

    fun locate(player: Player, vehicleId: UUID): LocateInfo {
        val record = index.byId(vehicleId) ?: return LocateInfo(LocateStatus.NOT_FOUND, null, false)
        if (!matchesOwner(player, record)) {
            return LocateInfo(LocateStatus.NOT_OWNER, record, false)
        }
        val entity = Bukkit.getEntity(record.entityUuid())
        val loaded = entity != null
        return LocateInfo(LocateStatus.OK, record, loaded)
    }

    fun remainingCooldownSeconds(playerUuid: UUID): Long = cooldowns.remainingSeconds(playerUuid)

    fun sendResultMessage(player: Player, result: Result, bound: UUID?) {
        val ph = HashMap<String, String>()
        when (result) {
            Result.SUCCESS -> {
                lang.send(player, "recall.success")
                return
            }
            Result.NOT_FOUND -> {
                lang.send(player, "recall.fail_not_found")
                return
            }
            Result.NOT_OWNER -> {
                lang.send(player, "recall.fail_not_owner")
                return
            }
            Result.ENTITY_NOT_LOADED -> {
                val rec = if (bound == null) null else index.byId(bound)
                if (rec != null && rec.world() != null) {
                    ph["world"] = rec.world() ?: ""
                    ph["x"] = String.format("%.0f", rec.x())
                    ph["y"] = String.format("%.0f", rec.y())
                    ph["z"] = String.format("%.0f", rec.z())
                    lang.send(player, "recall.fail_not_loaded_with_pos", ph)
                } else {
                    lang.send(player, "recall.fail_not_loaded")
                }
                return
            }
            Result.NO_NEARBY_OWNED -> {
                ph["radius"] = plugin.configManager().getRecallSearchRadius().toInt().toString()
                lang.send(player, "recall.fail_no_nearby", ph)
                return
            }
            Result.PROFILE_NO_SUMMON -> {
                lang.send(player, "recall.fail_profile_no_summon")
                return
            }
            Result.UNSAFE_DESTINATION -> {
                lang.send(player, "recall.fail_unsafe")
                return
            }
            Result.COOLDOWN -> {
                ph["seconds"] = remainingCooldownSeconds(player.uniqueId).toString()
                lang.send(player, "recall.fail_cooldown", ph)
                return
            }
            Result.NO_PERMISSION -> {
                lang.send(player, "recall.fail_no_permission")
                return
            }
        }
    }

    private fun matchesOwner(player: Player, record: VehicleRecord): Boolean {
        if (record.ownerUuid() == player.uniqueId) return true
        return player.hasPermission(OwnershipService.BYPASS_PERMISSION)
    }

    enum class LocateStatus { OK, NOT_FOUND, NOT_OWNER }

    class LocateInfo(
        private val status: LocateStatus,
        private val record: VehicleRecord?,
        private val loaded: Boolean,
    ) {
        fun status(): LocateStatus = status

        fun record(): VehicleRecord? = record

        fun loaded(): Boolean = loaded
    }

    companion object {
        const val KEY_USE_PERMISSION: String = "mountlicense.key.use"

        @JvmStatic
        fun isSafeDestination(loc: Location?): Boolean {
            if (loc == null || loc.world == null) return false
            val feet = loc.block
            val head = feet.getRelative(0, 1, 0)
            val ground = feet.getRelative(0, -1, 0)
            return isSafePassage(feet) && isSafePassage(head) && isSafeGround(ground)
        }

        private fun isSafePassage(block: Block?): Boolean {
            if (block == null) return false
            return block.isPassable && !block.isLiquid && !isHazardous(block.type)
        }

        private fun isSafeGround(block: Block?): Boolean {
            if (block == null) return false
            return block.type.isSolid && !block.isLiquid && !isHazardous(block.type)
        }

        private fun isHazardous(material: Material?): Boolean {
            if (material == null) return true
            return when (material) {
                Material.LAVA,
                Material.FIRE,
                Material.SOUL_FIRE,
                Material.CAMPFIRE,
                Material.SOUL_CAMPFIRE,
                Material.MAGMA_BLOCK,
                Material.CACTUS,
                Material.SWEET_BERRY_BUSH,
                Material.POWDER_SNOW,
                -> true
                else -> false
            }
        }
    }
}
