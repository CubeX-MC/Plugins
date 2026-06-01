package org.cubexmc.mountlicense.listener;

import java.util.UUID;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.cubexmc.mountlicense.MountLicensePlugin;
import org.cubexmc.mountlicense.config.ConfigManager.ParkMode;
import org.cubexmc.mountlicense.model.VehicleRecord;
import org.cubexmc.mountlicense.model.VehicleState;
import org.cubexmc.mountlicense.service.OwnershipService;
import org.cubexmc.mountlicense.service.ParkingService;
import org.spigotmc.event.entity.EntityDismountEvent;
import org.spigotmc.event.entity.EntityMountEvent;

public class AutoParkListener implements Listener {

    private final MountLicensePlugin plugin;
    private final OwnershipService ownership;
    private final ParkingService parking;

    public AutoParkListener(MountLicensePlugin plugin, OwnershipService ownership, ParkingService parking) {
        this.plugin = plugin;
        this.ownership = ownership;
        this.parking = parking;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (plugin.configManager().getParkMode() != ParkMode.AUTO) return;
        if (!(event.getEntity() instanceof Player player)) return;
        Entity mount = event.getMount();
        if (!ownership.isOwnerOrTrustee(mount, player.getUniqueId())) return;

        UUID vehicleId = ownership.readVehicleId(mount);
        if (vehicleId == null) return;
        VehicleRecord record = plugin.vehicleIndex().byId(vehicleId);
        if (record == null) return;
        if (record.state() == VehicleState.ACTIVE) return;
        if (record.state() == VehicleState.LOCKED) return;

        unparkDirect(record, mount);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (plugin.configManager().getParkMode() != ParkMode.AUTO) return;
        if (!(event.getEntity() instanceof Player player)) return;
        Entity mount = event.getDismounted();
        if (!ownership.isOwnerOrTrustee(mount, player.getUniqueId())) return;

        UUID vehicleId = ownership.readVehicleId(mount);
        if (vehicleId == null) return;
        VehicleRecord record = plugin.vehicleIndex().byId(vehicleId);
        if (record == null) return;
        if (record.state() == VehicleState.PARKED) return;
        if (record.state() == VehicleState.LOCKED) return;

        parkDirect(record, mount);
    }

    private void parkDirect(VehicleRecord record, Entity mount) {
        record.setState(VehicleState.PARKED);
        mount.getPersistentDataContainer().set(
                plugin.pdcKeys().state(),
                org.bukkit.persistence.PersistentDataType.STRING,
                VehicleState.PARKED.name());
        org.bukkit.Location loc = mount.getLocation();
        if (loc.getWorld() != null) {
            record.setLocation(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                    loc.getYaw(), loc.getPitch());
        }
        record.setLastSeenAt(System.currentTimeMillis());
        if (mount instanceof org.bukkit.entity.LivingEntity living && !(mount instanceof Player)) {
            living.setAI(false);
        }
        plugin.vehicleIndex().markDirty();
    }

    private void unparkDirect(VehicleRecord record, Entity mount) {
        record.setState(VehicleState.ACTIVE);
        mount.getPersistentDataContainer().set(
                plugin.pdcKeys().state(),
                org.bukkit.persistence.PersistentDataType.STRING,
                VehicleState.ACTIVE.name());
        record.setLastSeenAt(System.currentTimeMillis());
        if (mount instanceof org.bukkit.entity.LivingEntity living && !(mount instanceof Player)) {
            living.setAI(true);
        }
        plugin.vehicleIndex().markDirty();
    }
}
