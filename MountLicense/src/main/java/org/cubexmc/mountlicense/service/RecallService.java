package org.cubexmc.mountlicense.service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.cubexmc.mountlicense.MountLicensePlugin;
import org.cubexmc.mountlicense.config.ProfileRegistry;
import org.cubexmc.mountlicense.lang.LanguageManager;
import org.cubexmc.mountlicense.model.VehicleFeature;
import org.cubexmc.mountlicense.model.VehicleProfile;
import org.cubexmc.mountlicense.model.VehicleRecord;
import org.cubexmc.mountlicense.model.VehicleState;
import org.cubexmc.mountlicense.persistence.VehicleIndex;
import org.cubexmc.mountlicense.util.CooldownTracker;

public class RecallService {

    public static final String KEY_USE_PERMISSION = "mountlicense.key.use";

    private final MountLicensePlugin plugin;
    private final OwnershipService ownership;
    private final ProfileRegistry profiles;
    private final VehicleIndex index;
    private final LanguageManager lang;
    private final CooldownTracker cooldowns = new CooldownTracker();

    public RecallService(MountLicensePlugin plugin, OwnershipService ownership,
                         ProfileRegistry profiles, VehicleIndex index, LanguageManager lang) {
        this.plugin = plugin;
        this.ownership = ownership;
        this.profiles = profiles;
        this.index = index;
        this.lang = lang;
    }

    public enum Result {
        SUCCESS,
        NOT_FOUND,
        NOT_OWNER,
        ENTITY_NOT_LOADED,
        NO_NEARBY_OWNED,
        PROFILE_NO_SUMMON,
        UNSAFE_DESTINATION,
        COOLDOWN,
        NO_PERMISSION
    }

    public Result recallById(Player player, UUID vehicleId) {
        if (!player.hasPermission(KEY_USE_PERMISSION)) return Result.NO_PERMISSION;
        VehicleRecord record = index.byId(vehicleId);
        if (record == null) return Result.NOT_FOUND;
        if (!matchesOwner(player, record)) return Result.NOT_OWNER;

        VehicleProfile profile = profiles.byId(record.profile());
        if (profile != null && !profile.has(VehicleFeature.SUMMON)) {
            return Result.PROFILE_NO_SUMMON;
        }

        Entity entity = Bukkit.getEntity(record.entityUuid());
        if (entity == null) return Result.ENTITY_NOT_LOADED;

        return recallEntity(player, entity, record);
    }

    public Result recallNearest(Player player) {
        if (!player.hasPermission(KEY_USE_PERMISSION)) return Result.NO_PERMISSION;
        Entity nearest = findNearestOwnedLoaded(player);
        if (nearest == null) return Result.NO_NEARBY_OWNED;

        UUID vehicleId = ownership.readVehicleId(nearest);
        if (vehicleId == null) return Result.NOT_FOUND;
        VehicleRecord record = index.byId(vehicleId);
        if (record == null) return Result.NOT_FOUND;
        return recallEntity(player, nearest, record);
    }

    public Entity findNearestOwnedLoaded(Player player) {
        double radius = plugin.configManager().getRecallSearchRadius();
        UUID playerUuid = player.getUniqueId();

        Entity best = null;
        double bestDistSq = radius * radius;
        Location origin = player.getLocation();

        for (Entity e : player.getNearbyEntities(radius, radius, radius)) {
            if (!ownership.isOwner(e, playerUuid)) continue;
            String profileId = ownership.readProfileId(e);
            VehicleProfile profile = profiles.byId(profileId);
            if (profile != null && !profile.has(VehicleFeature.SUMMON)) continue;

            double dSq = e.getLocation().distanceSquared(origin);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = e;
            }
        }
        return best;
    }

    private Result recallEntity(Player player, Entity entity, VehicleRecord record) {
        if (!cooldowns.tryAcquire(player.getUniqueId(),
                plugin.configManager().getRecallCooldownSeconds())) {
            return Result.COOLDOWN;
        }

        Location destination = player.getLocation();
        if (plugin.configManager().isRecallRequireSafeDestination()
                && !isSafeDestination(destination)) {
            cooldowns.clear(player.getUniqueId());
            return Result.UNSAFE_DESTINATION;
        }

        entity.teleport(destination);

        if (plugin.configManager().isRecallWakeOnRecall()) {
            if (entity instanceof LivingEntity living && !(entity instanceof Player)) {
                living.setAI(true);
            }
            record.setState(VehicleState.ACTIVE);
            entity.getPersistentDataContainer().set(
                    plugin.pdcKeys().state(),
                    org.bukkit.persistence.PersistentDataType.STRING,
                    VehicleState.ACTIVE.name());
        }

        if (destination.getWorld() != null) {
            record.setLocation(destination.getWorld().getName(),
                    destination.getX(), destination.getY(), destination.getZ(),
                    destination.getYaw(), destination.getPitch());
        }
        record.setLastSeenAt(System.currentTimeMillis());
        index.markDirty();
        return Result.SUCCESS;
    }

    public LocateInfo locate(Player player, UUID vehicleId) {
        VehicleRecord record = index.byId(vehicleId);
        if (record == null) return new LocateInfo(LocateStatus.NOT_FOUND, null, false);
        if (!matchesOwner(player, record)) {
            return new LocateInfo(LocateStatus.NOT_OWNER, record, false);
        }
        Entity entity = Bukkit.getEntity(record.entityUuid());
        boolean loaded = entity != null;
        return new LocateInfo(LocateStatus.OK, record, loaded);
    }

    public long remainingCooldownSeconds(UUID playerUuid) {
        return cooldowns.remainingSeconds(playerUuid);
    }

    public void sendResultMessage(Player player, Result result, UUID bound) {
        Map<String, String> ph = new HashMap<>();
        switch (result) {
            case SUCCESS:
                lang.send(player, "recall.success");
                return;
            case NOT_FOUND:
                lang.send(player, "recall.fail_not_found");
                return;
            case NOT_OWNER:
                lang.send(player, "recall.fail_not_owner");
                return;
            case ENTITY_NOT_LOADED:
                VehicleRecord rec = bound == null ? null : index.byId(bound);
                if (rec != null && rec.world() != null) {
                    ph.put("world", rec.world());
                    ph.put("x", String.format("%.0f", rec.x()));
                    ph.put("y", String.format("%.0f", rec.y()));
                    ph.put("z", String.format("%.0f", rec.z()));
                    lang.send(player, "recall.fail_not_loaded_with_pos", ph);
                } else {
                    lang.send(player, "recall.fail_not_loaded");
                }
                return;
            case NO_NEARBY_OWNED:
                ph.put("radius", String.valueOf((int) plugin.configManager().getRecallSearchRadius()));
                lang.send(player, "recall.fail_no_nearby", ph);
                return;
            case PROFILE_NO_SUMMON:
                lang.send(player, "recall.fail_profile_no_summon");
                return;
            case UNSAFE_DESTINATION:
                lang.send(player, "recall.fail_unsafe");
                return;
            case COOLDOWN:
                ph.put("seconds", String.valueOf(remainingCooldownSeconds(player.getUniqueId())));
                lang.send(player, "recall.fail_cooldown", ph);
                return;
            case NO_PERMISSION:
                lang.send(player, "recall.fail_no_permission");
                return;
        }
    }

    private boolean matchesOwner(Player player, VehicleRecord record) {
        if (record.ownerUuid().equals(player.getUniqueId())) return true;
        return player.hasPermission(OwnershipService.BYPASS_PERMISSION);
    }

    static boolean isSafeDestination(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        Block feet = loc.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);
        return isSafePassage(feet) && isSafePassage(head) && isSafeGround(ground);
    }

    private static boolean isSafePassage(Block block) {
        if (block == null) return false;
        return block.isPassable() && !block.isLiquid() && !isHazardous(block.getType());
    }

    private static boolean isSafeGround(Block block) {
        if (block == null) return false;
        return block.getType().isSolid() && !block.isLiquid() && !isHazardous(block.getType());
    }

    private static boolean isHazardous(Material material) {
        if (material == null) return true;
        return switch (material) {
            case LAVA, FIRE, SOUL_FIRE, CAMPFIRE, SOUL_CAMPFIRE,
                    MAGMA_BLOCK, CACTUS, SWEET_BERRY_BUSH, POWDER_SNOW -> true;
            default -> false;
        };
    }

    public enum LocateStatus { OK, NOT_FOUND, NOT_OWNER }

    public static final class LocateInfo {
        private final LocateStatus status;
        private final VehicleRecord record;
        private final boolean loaded;

        public LocateInfo(LocateStatus status, VehicleRecord record, boolean loaded) {
            this.status = status;
            this.record = record;
            this.loaded = loaded;
        }

        public LocateStatus status() { return status; }
        public VehicleRecord record() { return record; }
        public boolean loaded() { return loaded; }
    }
}
