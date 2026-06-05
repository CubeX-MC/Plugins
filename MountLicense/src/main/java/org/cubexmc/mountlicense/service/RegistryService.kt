package org.cubexmc.mountlicense.service

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.AbstractHorse
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Steerable
import org.bukkit.entity.Tameable
import org.bukkit.entity.Vehicle
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.mountlicense.MountLicensePlugin
import org.cubexmc.mountlicense.config.ProfileRegistry
import org.cubexmc.mountlicense.integration.EconomyHook
import org.cubexmc.mountlicense.lang.LanguageManager
import org.cubexmc.mountlicense.model.VehicleProfile
import org.cubexmc.mountlicense.model.VehicleRecord
import org.cubexmc.mountlicense.persistence.VehicleIndex
import org.cubexmc.mountlicense.util.CooldownTracker
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.logging.Level

class RegistryService(
    private val plugin: MountLicensePlugin,
    private val keys: PdcKeys,
    private val index: VehicleIndex,
    private val profiles: ProfileRegistry,
    private val lang: LanguageManager,
) {
    private val cooldowns = CooldownTracker()
    private var economy: EconomyHook? = null

    private fun economy(): EconomyHook {
        if (economy == null) economy = EconomyHook(plugin)
        return economy ?: EconomyHook(plugin).also { economy = it }
    }

    enum class Result {
        SUCCESS,
        NO_PROFILE,
        ALREADY_REGISTERED,
        NOT_TAMED,
        NOT_OWNER,
        NOT_EMPTY,
        NO_LICENSE,
        NO_PERMISSION,
        COOLDOWN,
        NOT_ENOUGH_MONEY,
        MAX_REACHED,
        REQUIRES_SADDLE,
        FAILED,
    }

    fun tryRegister(player: Player, target: Entity?, handItem: ItemStack?): Result {
        if (!player.hasPermission("mountlicense.register")) {
            send(player, "registration.fail_no_permission", null)
            return Result.NO_PERMISSION
        }
        if (!plugin.itemFactory().isLicense(handItem)) {
            send(player, "registration.fail_no_license", null)
            return Result.NO_LICENSE
        }
        if (!cooldowns.tryAcquire(player.uniqueId, plugin.configManager().getRegisterCooldownSeconds())) {
            val p = HashMap<String, String>()
            p["seconds"] = cooldowns.remainingSeconds(player.uniqueId).toString()
            send(player, "registration.fail_cooldown", p)
            return Result.COOLDOWN
        }
        if (target == null) return Result.NO_PROFILE

        val profile = profiles.byEntityType(target.type)
        if (profile == null) {
            send(player, "registration.fail_no_profile", null)
            return Result.NO_PROFILE
        }

        if (plugin.configManager().isRejectAlreadyRegistered() && hasRegistration(target)) {
            send(player, "registration.fail_already_registered", null)
            return Result.ALREADY_REGISTERED
        }

        val maxPerPlayer = plugin.configManager().getMaxVehiclesPerPlayer()
        if (maxPerPlayer > 0 && !player.hasPermission(OwnershipService.BYPASS_PERMISSION)) {
            val current = index.byOwner(player.uniqueId).size
            if (current >= maxPerPlayer) {
                val p = HashMap<String, String>()
                p["max"] = maxPerPlayer.toString()
                p["current"] = current.toString()
                send(player, "registration.fail_max_vehicles", p)
                return Result.MAX_REACHED
            }
        }

        if (profile.requiresTamedOwner() && target is Tameable) {
            if (!target.isTamed) {
                send(player, "registration.fail_not_tamed", null)
                return Result.NOT_TAMED
            }
            val owner = target.owner
            if (owner == null || owner.uniqueId != player.uniqueId) {
                send(player, "registration.fail_not_owner", null)
                return Result.NOT_OWNER
            }
        }

        if (profile.requiresSaddle() && (target !is Steerable || !target.hasSaddle())) {
            send(player, "registration.fail_requires_saddle", null)
            return Result.REQUIRES_SADDLE
        }

        if (plugin.configManager().isRequireEmptyVehicle() &&
            (target is Vehicle || target is AbstractHorse) &&
            target.passengers.isNotEmpty()
        ) {
            send(player, "registration.fail_not_empty", null)
            return Result.NOT_EMPTY
        }

        val cost = plugin.configManager().getRegisterCost()
        var charged = false
        if (cost > 0 && economy().isReady()) {
            if (!economy().has(player, cost)) {
                val p = HashMap<String, String>()
                p["amount"] = formatMoney(cost)
                send(player, "registration.fail_economy", p)
                return Result.NOT_ENOUGH_MONEY
            }
            if (!economy().withdraw(player, cost)) {
                val p = HashMap<String, String>()
                p["amount"] = formatMoney(cost)
                send(player, "registration.fail_economy", p)
                return Result.NOT_ENOUGH_MONEY
            }
            charged = true
            val p = HashMap<String, String>()
            p["amount"] = formatMoney(cost)
            send(player, "registration.charged", p)
        }

        val vehicleId = UUID.randomUUID()
        val record: VehicleRecord
        try {
            record = writeAndIndex(player, target, profile, vehicleId)
            if (handItem != null && handItem.amount > 1) {
                handItem.amount = handItem.amount - 1
            } else {
                player.inventory.setItemInMainHand(null)
            }
        } catch (ex: RuntimeException) {
            rollbackRegistration(target, vehicleId)
            if (charged && !economy().deposit(player, cost)) {
                plugin.logger.warning(
                    "Registration failed after charging ${player.name}; automatic refund of ${formatMoney(cost)} failed.",
                )
            }
            cooldowns.clear(player.uniqueId)
            plugin.logger.log(Level.SEVERE, "Failed to register vehicle $vehicleId", ex)
            send(player, "registration.fail_internal", null)
            return Result.FAILED
        }

        val p = HashMap<String, String>()
        p["profile"] = profile.id()
        p["vehicle_id"] = record.shortId()
        send(player, "registration.success", p)

        return Result.SUCCESS
    }

    private fun writeAndIndex(player: Player, target: Entity, profile: VehicleProfile, vehicleId: UUID): VehicleRecord {
        val now = System.currentTimeMillis()
        val plate = generatePlate()

        val pdc: PersistentDataContainer = target.persistentDataContainer
        pdc.set(keys.vehicleId(), PersistentDataType.STRING, vehicleId.toString())
        pdc.set(keys.ownerUuid(), PersistentDataType.STRING, player.uniqueId.toString())
        pdc.set(keys.profile(), PersistentDataType.STRING, profile.id())
        pdc.set(keys.state(), PersistentDataType.STRING, "ACTIVE")
        pdc.set(keys.createdAt(), PersistentDataType.LONG, now)
        pdc.set(keys.schemaVersion(), PersistentDataType.INTEGER, PdcKeys.SCHEMA_VERSION)

        val record = VehicleRecord(vehicleId, target.uniqueId, target.type.name, player.uniqueId, profile.id())
        record.setPlate(plate)
        val loc: Location = target.location
        if (loc.world != null) {
            record.setLocation(loc.world?.name, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
        }
        record.setCreatedAt(now)
        record.setLastSeenAt(now)

        applyDisplayName(record, target, player.name)

        if (target is LivingEntity) {
            target.removeWhenFarAway = false
        }
        target.isPersistent = true
        target.addScoreboardTag("mountlicense_registered")

        index.put(record)

        if (plugin.configManager().isDebug()) {
            plugin.logger.info("Registered vehicle $vehicleId (${profile.id()}) for ${player.name}")
        }
        return record
    }

    fun refreshLoadedDisplayNames(): Int {
        var refreshed = 0
        for (record in index.all()) {
            val entity = Bukkit.getEntity(record.entityUuid()) ?: continue

            val ownerName = Bukkit.getOfflinePlayer(record.ownerUuid()).name
            applyDisplayName(record, entity, ownerName ?: "")
            refreshed++
        }
        if (refreshed > 0) {
            index.markDirty()
        }
        return refreshed
    }

    private fun applyDisplayName(record: VehicleRecord, target: Entity, ownerName: String?) {
        val format = plugin.configManager().getDisplayNameFormat()
        if (format.isEmpty()) return

        val name = format.replace("%player%", ownerName ?: "")
            .replace("%profile%", record.profile() ?: "")
            .replace("%plate%", record.plate())
        target.customName = name
        target.isCustomNameVisible = false
        record.setDisplayName(name)
    }

    private fun generatePlate(): String {
        for (attempt in 0 until 20) {
            val plate = randomPlate()
            var taken = false
            for (rec in index.all()) {
                if (plate.equals(rec.plate(), ignoreCase = true)) {
                    taken = true
                    break
                }
            }
            if (!taken) return plate
        }
        return randomPlate()
    }

    private fun randomPlate(): String {
        val random = ThreadLocalRandom.current()
        val out = StringBuilder(7)
        for (i in 0 until 3) {
            out.append(PLATE_LETTERS[random.nextInt(PLATE_LETTERS.length)])
        }
        out.append('-')
        out.append(random.nextInt(10))
        out.append(random.nextInt(10))
        out.append(random.nextInt(10))
        return out.toString()
    }

    private fun rollbackRegistration(target: Entity?, vehicleId: UUID?) {
        if (target != null) {
            val pdc = target.persistentDataContainer
            pdc.remove(keys.vehicleId())
            pdc.remove(keys.ownerUuid())
            pdc.remove(keys.profile())
            pdc.remove(keys.state())
            pdc.remove(keys.createdAt())
            pdc.remove(keys.schemaVersion())
            target.removeScoreboardTag("mountlicense_registered")
        }
        if (vehicleId != null) {
            index.remove(vehicleId)
        }
    }

    fun hasRegistration(entity: Entity?): Boolean {
        if (entity == null) return false
        return entity.persistentDataContainer.has(keys.vehicleId(), PersistentDataType.STRING)
    }

    fun readVehicleId(entity: Entity?): UUID? {
        if (entity == null) return null
        val raw = entity.persistentDataContainer.get(keys.vehicleId(), PersistentDataType.STRING)
        return try {
            if (raw == null) null else UUID.fromString(raw)
        } catch (ex: IllegalArgumentException) {
            null
        }
    }

    fun reindexLoadedEntities(): ReindexResult {
        var scanned = 0
        var recovered = 0
        for (world in Bukkit.getWorlds()) {
            for (e in world.entities) {
                scanned++
                val vid = readVehicleId(e) ?: continue
                if (index.byId(vid) != null) continue

                val ownerRaw = e.persistentDataContainer.get(keys.ownerUuid(), PersistentDataType.STRING)
                val profileId = e.persistentDataContainer.get(keys.profile(), PersistentDataType.STRING)
                val createdAt = e.persistentDataContainer.get(keys.createdAt(), PersistentDataType.LONG)
                if (ownerRaw == null || profileId == null) continue

                try {
                    val owner = UUID.fromString(ownerRaw)
                    val rec = VehicleRecord(vid, e.uniqueId, e.type.name, owner, profileId)
                    if (createdAt != null) rec.setCreatedAt(createdAt)
                    rec.setLastSeenAt(System.currentTimeMillis())
                    val loc = e.location
                    if (loc.world != null) {
                        rec.setLocation(loc.world?.name, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
                    }
                    index.put(rec)
                    recovered++
                } catch (ex: IllegalArgumentException) {
                    plugin.logger.warning("Bad owner UUID on entity ${e.uniqueId}: $ownerRaw")
                }
            }
        }
        return ReindexResult(scanned, recovered)
    }

    private fun formatMoney(amount: Double): String = String.format("%.2f", amount)

    private fun send(player: Player, key: String, placeholders: Map<String, String>?) {
        lang.send(player, key, placeholders)
    }

    private companion object {
        const val PLATE_LETTERS: String = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    }
}
