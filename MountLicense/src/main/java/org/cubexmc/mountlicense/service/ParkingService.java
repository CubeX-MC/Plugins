package org.cubexmc.mountlicense.service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.cubexmc.mountlicense.MountLicensePlugin;
import org.cubexmc.mountlicense.lang.LanguageManager;
import org.cubexmc.mountlicense.model.VehicleRecord;
import org.cubexmc.mountlicense.model.VehicleState;
import org.cubexmc.mountlicense.persistence.VehicleIndex;

public class ParkingService {

    private static final long CONFIRM_WINDOW_MS = 30_000L;

    private final MountLicensePlugin plugin;
    private final PdcKeys keys;
    private final VehicleIndex index;
    private final OwnershipService ownership;
    private final LanguageManager lang;
    private final Map<UUID, PendingRelease> pendingReleases = new ConcurrentHashMap<>();

    public ParkingService(MountLicensePlugin plugin, PdcKeys keys, VehicleIndex index,
                          OwnershipService ownership, LanguageManager lang) {
        this.plugin = plugin;
        this.keys = keys;
        this.index = index;
        this.ownership = ownership;
        this.lang = lang;
    }

    public enum ActionResult {
        SUCCESS,
        NOT_REGISTERED,
        NOT_OWNER,
        ALREADY_IN_STATE,
        CONFIRM_REQUIRED,
        CONFIRMED
    }

    public ActionResult park(Player player, Entity entity) {
        return transitionState(player, entity, VehicleState.PARKED, true);
    }

    public ActionResult unpark(Player player, Entity entity) {
        return transitionState(player, entity, VehicleState.ACTIVE, true);
    }

    public ActionResult lock(Player player, Entity entity) {
        return transitionState(player, entity, VehicleState.LOCKED, false);
    }

    public ActionResult unlock(Player player, Entity entity) {
        return transitionState(player, entity, VehicleState.ACTIVE, false);
    }

    public ActionResult release(Player player, Entity entity) {
        if (entity == null || !ownership.isRegistered(entity)) return ActionResult.NOT_REGISTERED;
        if (!ownership.canManage(player, entity)) return ActionResult.NOT_OWNER;

        UUID vehicleId = ownership.readVehicleId(entity);
        long now = System.currentTimeMillis();
        PendingRelease pending = pendingReleases.get(player.getUniqueId());
        if (pending != null && pending.vehicleId.equals(vehicleId)
                && now - pending.requestedAt < CONFIRM_WINDOW_MS) {
            pendingReleases.remove(player.getUniqueId());
            doRelease(entity, vehicleId);
            return ActionResult.CONFIRMED;
        }

        pendingReleases.put(player.getUniqueId(), new PendingRelease(vehicleId, now));
        return ActionResult.CONFIRM_REQUIRED;
    }

    private void doRelease(Entity entity, UUID vehicleId) {
        entity.getPersistentDataContainer().remove(keys.vehicleId());
        entity.getPersistentDataContainer().remove(keys.ownerUuid());
        entity.getPersistentDataContainer().remove(keys.profile());
        entity.getPersistentDataContainer().remove(keys.state());
        entity.getPersistentDataContainer().remove(keys.createdAt());
        entity.removeScoreboardTag("mountlicense_registered");
        entity.setCustomName(null);
        if (entity instanceof LivingEntity living) {
            living.setAI(true);
            living.setRemoveWhenFarAway(true);
        }
        index.remove(vehicleId);
    }

    private ActionResult transitionState(Player player, Entity entity, VehicleState target, boolean adjustAi) {
        if (entity == null || !ownership.isRegistered(entity)) return ActionResult.NOT_REGISTERED;
        if (!ownership.canManage(player, entity)) return ActionResult.NOT_OWNER;

        UUID vehicleId = ownership.readVehicleId(entity);
        VehicleRecord record = index.byId(vehicleId);
        if (record != null && record.state() == target) return ActionResult.ALREADY_IN_STATE;

        entity.getPersistentDataContainer().set(keys.state(), PersistentDataType.STRING, target.name());
        if (record != null) {
            record.setState(target);
            Location loc = entity.getLocation();
            if (loc.getWorld() != null) {
                record.setLocation(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                        loc.getYaw(), loc.getPitch());
            }
            record.setLastSeenAt(System.currentTimeMillis());
            index.markDirty();
        }

        if (adjustAi && entity instanceof LivingEntity living && !(entity instanceof Player)) {
            living.setAI(target == VehicleState.ACTIVE);
        }
        return ActionResult.SUCCESS;
    }

    public Map<String, String> placeholdersFor(VehicleRecord record, Entity entity) {
        Map<String, String> p = new HashMap<>();
        if (record != null) {
            p.put("vehicle_id", record.shortId());
            p.put("profile", record.profile());
            p.put("state", record.state().name());
        } else if (entity != null) {
            p.put("entity_type", entity.getType().name());
        }
        return p;
    }

    public boolean hasPendingRelease(UUID playerUuid) {
        PendingRelease p = pendingReleases.get(playerUuid);
        return p != null && System.currentTimeMillis() - p.requestedAt < CONFIRM_WINDOW_MS;
    }

    public void cancelPending(UUID playerUuid) {
        pendingReleases.remove(playerUuid);
    }

    private static final class PendingRelease {
        final UUID vehicleId;
        final long requestedAt;

        PendingRelease(UUID vehicleId, long requestedAt) {
            this.vehicleId = vehicleId;
            this.requestedAt = requestedAt;
        }
    }

    /** Convenience: find what the player is looking at, if any. */
    public Entity findTargeted(Player player, double maxDistance) {
        org.bukkit.util.RayTraceResult ray = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                maxDistance,
                e -> e != player);
        return ray == null ? null : ray.getHitEntity();
    }

    public Entity findEntityForVehicle(VehicleRecord record) {
        if (record == null || record.entityUuid() == null) return null;
        Entity e = Bukkit.getEntity(record.entityUuid());
        return e;
    }
}
