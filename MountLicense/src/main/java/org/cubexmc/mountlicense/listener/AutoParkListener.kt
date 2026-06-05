package org.cubexmc.mountlicense.listener

import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.cubexmc.mountlicense.MountLicensePlugin
import org.cubexmc.mountlicense.config.ConfigManager.ParkMode
import org.cubexmc.mountlicense.model.VehicleRecord
import org.cubexmc.mountlicense.model.VehicleState
import org.cubexmc.mountlicense.service.OwnershipService
import org.cubexmc.mountlicense.service.ParkingService
import org.spigotmc.event.entity.EntityDismountEvent
import org.spigotmc.event.entity.EntityMountEvent

class AutoParkListener(
    private val plugin: MountLicensePlugin,
    private val ownership: OwnershipService,
    @Suppress("unused") private val parking: ParkingService,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMount(event: EntityMountEvent) {
        if (plugin.configManager().getParkMode() != ParkMode.AUTO) return
        val player = event.entity as? Player ?: return
        val mount = event.mount
        if (!ownership.isOwnerOrTrustee(mount, player.uniqueId)) return

        val vehicleId = ownership.readVehicleId(mount) ?: return
        val record = plugin.vehicleIndex().byId(vehicleId) ?: return
        if (record.state() == VehicleState.ACTIVE) return
        if (record.state() == VehicleState.LOCKED) return

        unparkDirect(record, mount)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDismount(event: EntityDismountEvent) {
        if (plugin.configManager().getParkMode() != ParkMode.AUTO) return
        val player = event.entity as? Player ?: return
        val mount = event.dismounted
        if (!ownership.isOwnerOrTrustee(mount, player.uniqueId)) return

        val vehicleId = ownership.readVehicleId(mount) ?: return
        val record = plugin.vehicleIndex().byId(vehicleId) ?: return
        if (record.state() == VehicleState.PARKED) return
        if (record.state() == VehicleState.LOCKED) return

        parkDirect(record, mount)
    }

    private fun parkDirect(record: VehicleRecord, mount: Entity) {
        record.setState(VehicleState.PARKED)
        mount.persistentDataContainer.set(plugin.pdcKeys().state(), PersistentDataType.STRING, VehicleState.PARKED.name)
        val loc = mount.location
        if (loc.world != null) {
            record.setLocation(loc.world?.name, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
        }
        record.setLastSeenAt(System.currentTimeMillis())
        if (mount is LivingEntity && mount !is Player) {
            mount.setAI(false)
        }
        plugin.vehicleIndex().markDirty()
    }

    private fun unparkDirect(record: VehicleRecord, mount: Entity) {
        record.setState(VehicleState.ACTIVE)
        mount.persistentDataContainer.set(plugin.pdcKeys().state(), PersistentDataType.STRING, VehicleState.ACTIVE.name)
        record.setLastSeenAt(System.currentTimeMillis())
        if (mount is LivingEntity && mount !is Player) {
            mount.setAI(true)
        }
        plugin.vehicleIndex().markDirty()
    }
}
