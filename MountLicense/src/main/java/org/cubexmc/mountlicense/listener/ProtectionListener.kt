package org.cubexmc.mountlicense.listener

import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerLeashEntityEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.vehicle.VehicleDamageEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.inventory.InventoryHolder
import org.bukkit.projectiles.ProjectileSource
import org.cubexmc.mountlicense.MountLicensePlugin
import org.cubexmc.mountlicense.lang.LanguageManager
import org.cubexmc.mountlicense.service.OwnershipService
import org.spigotmc.event.entity.EntityMountEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ProtectionListener(
    private val plugin: MountLicensePlugin,
    private val ownership: OwnershipService,
    private val lang: LanguageManager,
) : Listener {
    private val lastNotice: MutableMap<UUID, Long> = ConcurrentHashMap()

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityMount(event: EntityMountEvent) {
        if (!plugin.configManager().isProtectMount()) return
        val player = event.entity as? Player ?: return
        val mount = event.mount
        if (!ownership.isRegistered(mount)) return
        if (ownership.canAccess(player, mount)) return
        event.isCancelled = true
        notify(player, "protection.mount_blocked", mount)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onVehicleEnter(event: VehicleEnterEvent) {
        if (!plugin.configManager().isProtectMount()) return
        val player = event.entered as? Player ?: return
        val vehicle = event.vehicle
        if (!ownership.isRegistered(vehicle)) return
        if (ownership.canAccess(player, vehicle)) return
        event.isCancelled = true
        notify(player, "protection.mount_blocked", vehicle)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityDamage(event: EntityDamageByEntityEvent) {
        if (!plugin.configManager().isProtectDamage()) return
        val victim = event.entity
        if (!ownership.isRegistered(victim)) return
        val attacker = resolvePlayerAttacker(event.damager) ?: return
        if (attacker.hasPermission(OwnershipService.BYPASS_PERMISSION)) return
        event.isCancelled = true
        notify(attacker, "protection.damage_blocked", victim)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onVehicleDamage(event: VehicleDamageEvent) {
        if (!plugin.configManager().isProtectDamage()) return
        val vehicle = event.vehicle
        if (!ownership.isRegistered(vehicle)) return
        val attacker = resolvePlayerAttacker(event.attacker) ?: return
        if (attacker.hasPermission(OwnershipService.BYPASS_PERMISSION)) return
        event.isCancelled = true
        notify(attacker, "protection.damage_blocked", vehicle)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onVehicleDestroy(event: VehicleDestroyEvent) {
        val vehicle = event.vehicle
        if (!ownership.isRegistered(vehicle)) return
        val attacker = resolvePlayerAttacker(event.attacker)
        if (attacker != null && !attacker.hasPermission(OwnershipService.BYPASS_PERMISSION)) {
            if (plugin.configManager().isProtectDestroy()) {
                event.isCancelled = true
                notify(attacker, "protection.destroy_blocked", vehicle)
                return
            }
        }
        if (plugin.configManager().isCleanupOnDeath()) {
            cleanup(vehicle)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        if (!plugin.configManager().isProtectInventory()) return
        val player = event.player as? Player ?: return
        val holder: InventoryHolder? = event.inventory.holder
        val entity = holder as? Entity ?: return
        if (!ownership.isRegistered(entity)) return
        if (ownership.canAccess(player, entity)) return
        event.isCancelled = true
        notify(player, "protection.inventory_blocked", entity)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onLeash(event: PlayerLeashEntityEvent) {
        if (!plugin.configManager().isProtectLeash()) return
        val entity = event.entity
        if (!ownership.isRegistered(entity)) return
        if (ownership.canAccess(event.player, entity)) return
        event.isCancelled = true
        notify(event.player, "protection.leash_blocked", entity)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        if (!plugin.configManager().isCleanupOnDeath()) return
        cleanup(event.entity)
    }

    private fun resolvePlayerAttacker(damager: Entity?): Player? {
        if (damager == null) return null
        if (damager is Player) return damager
        if (damager is Projectile) {
            val shooter: ProjectileSource? = damager.shooter
            if (shooter is Player) return shooter
        }
        return null
    }

    private fun cleanup(entity: Entity) {
        val vehicleId = ownership.readVehicleId(entity) ?: return
        val removed = plugin.vehicleIndex().remove(vehicleId)
        if (removed != null && plugin.configManager().isDebug()) {
            plugin.logger.info("Cleaned up registration $vehicleId after entity ${entity.uniqueId} was destroyed.")
        }
    }

    private fun notify(player: Player, key: String, entity: Entity) {
        if (!plugin.configManager().isNotifyBlocked()) return
        val now = System.currentTimeMillis()
        val last = lastNotice[player.uniqueId]
        if (last != null && now - last < plugin.configManager().getNotifyDebounceMs()) return
        lastNotice[player.uniqueId] = now

        val p = HashMap<String, String>()
        p["entity_type"] = entity.type.name
        val ownerName = readOwnerName(entity)
        p["owner"] = ownerName ?: lang.msg("general.unknown_player")
        lang.send(player, key, p)
    }

    private fun readOwnerName(entity: Entity): String? {
        val owner = ownership.readOwner(entity) ?: return null
        return Bukkit.getOfflinePlayer(owner).name
    }
}
