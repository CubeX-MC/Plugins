package org.cubexmc.mountlicense.listener;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.projectiles.ProjectileSource;
import org.cubexmc.mountlicense.MountLicensePlugin;
import org.cubexmc.mountlicense.lang.LanguageManager;
import org.cubexmc.mountlicense.model.VehicleRecord;
import org.cubexmc.mountlicense.service.OwnershipService;
import org.spigotmc.event.entity.EntityMountEvent;

public class ProtectionListener implements Listener {

    private final MountLicensePlugin plugin;
    private final OwnershipService ownership;
    private final LanguageManager lang;
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();

    public ProtectionListener(MountLicensePlugin plugin, OwnershipService ownership, LanguageManager lang) {
        this.plugin = plugin;
        this.ownership = ownership;
        this.lang = lang;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityMount(EntityMountEvent event) {
        if (!plugin.configManager().isProtectMount()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        Entity mount = event.getMount();
        if (!ownership.isRegistered(mount)) return;
        if (ownership.canAccess(player, mount)) return;
        event.setCancelled(true);
        notify(player, "protection.mount_blocked", mount);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (!plugin.configManager().isProtectMount()) return;
        if (!(event.getEntered() instanceof Player player)) return;
        Entity vehicle = event.getVehicle();
        if (!ownership.isRegistered(vehicle)) return;
        if (ownership.canAccess(player, vehicle)) return;
        event.setCancelled(true);
        notify(player, "protection.mount_blocked", vehicle);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!plugin.configManager().isProtectDamage()) return;
        Entity victim = event.getEntity();
        if (!ownership.isRegistered(victim)) return;
        Player attacker = resolvePlayerAttacker(event.getDamager());
        if (attacker == null) return;
        if (attacker.hasPermission(OwnershipService.BYPASS_PERMISSION)) return;
        event.setCancelled(true);
        notify(attacker, "protection.damage_blocked", victim);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        if (!plugin.configManager().isProtectDamage()) return;
        Entity vehicle = event.getVehicle();
        if (!ownership.isRegistered(vehicle)) return;
        Player attacker = resolvePlayerAttacker(event.getAttacker());
        if (attacker == null) return;
        if (attacker.hasPermission(OwnershipService.BYPASS_PERMISSION)) return;
        event.setCancelled(true);
        notify(attacker, "protection.damage_blocked", vehicle);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        Entity vehicle = event.getVehicle();
        if (!ownership.isRegistered(vehicle)) return;
        Player attacker = resolvePlayerAttacker(event.getAttacker());
        if (attacker != null && !attacker.hasPermission(OwnershipService.BYPASS_PERMISSION)) {
            if (plugin.configManager().isProtectDestroy()) {
                event.setCancelled(true);
                notify(attacker, "protection.destroy_blocked", vehicle);
                return;
            }
        }
        if (plugin.configManager().isCleanupOnDeath()) {
            cleanup(vehicle);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!plugin.configManager().isProtectInventory()) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof Entity entity)) return;
        if (!ownership.isRegistered(entity)) return;
        if (ownership.canAccess(player, entity)) return;
        event.setCancelled(true);
        notify(player, "protection.inventory_blocked", entity);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent event) {
        if (!plugin.configManager().isProtectLeash()) return;
        Entity entity = event.getEntity();
        if (!ownership.isRegistered(entity)) return;
        if (ownership.canAccess(event.getPlayer(), entity)) return;
        event.setCancelled(true);
        notify(event.getPlayer(), "protection.leash_blocked", entity);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!plugin.configManager().isCleanupOnDeath()) return;
        cleanup(event.getEntity());
    }

    private Player resolvePlayerAttacker(Entity damager) {
        if (damager == null) return null;
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player p) return p;
        }
        return null;
    }

    private void cleanup(Entity entity) {
        UUID vehicleId = ownership.readVehicleId(entity);
        if (vehicleId == null) return;
        VehicleRecord removed = plugin.vehicleIndex().remove(vehicleId);
        if (removed != null && plugin.configManager().isDebug()) {
            plugin.getLogger().info("Cleaned up registration " + vehicleId
                    + " after entity " + entity.getUniqueId() + " was destroyed.");
        }
    }

    private void notify(Player player, String key, Entity entity) {
        if (!plugin.configManager().isNotifyBlocked()) return;
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(player.getUniqueId());
        if (last != null && now - last < plugin.configManager().getNotifyDebounceMs()) return;
        lastNotice.put(player.getUniqueId(), now);

        Map<String, String> p = new HashMap<>();
        p.put("entity_type", entity.getType().name());
        String ownerName = readOwnerName(entity);
        p.put("owner", ownerName == null ? lang.msg("general.unknown_player") : ownerName);
        lang.send(player, key, p);
    }

    private String readOwnerName(Entity entity) {
        UUID owner = ownership.readOwner(entity);
        if (owner == null) return null;
        return org.bukkit.Bukkit.getOfflinePlayer(owner).getName();
    }
}
