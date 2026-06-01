package org.cubexmc.mountlicense.service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Steerable;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Vehicle;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.cubexmc.mountlicense.MountLicensePlugin;
import org.cubexmc.mountlicense.config.ProfileRegistry;
import org.cubexmc.mountlicense.integration.EconomyHook;
import org.cubexmc.mountlicense.lang.LanguageManager;
import org.cubexmc.mountlicense.model.VehicleProfile;
import org.cubexmc.mountlicense.model.VehicleRecord;
import org.cubexmc.mountlicense.persistence.VehicleIndex;
import org.cubexmc.mountlicense.util.CooldownTracker;

public class RegistryService {

    private static final String PLATE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";

    private final MountLicensePlugin plugin;
    private final PdcKeys keys;
    private final VehicleIndex index;
    private final ProfileRegistry profiles;
    private final LanguageManager lang;
    private final CooldownTracker cooldowns = new CooldownTracker();
    private EconomyHook economy;

    public RegistryService(MountLicensePlugin plugin, PdcKeys keys, VehicleIndex index,
                           ProfileRegistry profiles, LanguageManager lang) {
        this.plugin = plugin;
        this.keys = keys;
        this.index = index;
        this.profiles = profiles;
        this.lang = lang;
    }

    private EconomyHook economy() {
        if (economy == null) economy = new EconomyHook(plugin);
        return economy;
    }

    public enum Result {
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
        FAILED
    }

    public Result tryRegister(Player player, Entity target, ItemStack handItem) {
        if (!player.hasPermission("mountlicense.register")) {
            send(player, "registration.fail_no_permission", null);
            return Result.NO_PERMISSION;
        }
        if (!plugin.itemFactory().isLicense(handItem)) {
            send(player, "registration.fail_no_license", null);
            return Result.NO_LICENSE;
        }
        if (!cooldowns.tryAcquire(player.getUniqueId(),
                plugin.configManager().getRegisterCooldownSeconds())) {
            Map<String, String> p = new HashMap<>();
            p.put("seconds", String.valueOf(cooldowns.remainingSeconds(player.getUniqueId())));
            send(player, "registration.fail_cooldown", p);
            return Result.COOLDOWN;
        }
        if (target == null) return Result.NO_PROFILE;

        VehicleProfile profile = profiles.byEntityType(target.getType());
        if (profile == null) {
            send(player, "registration.fail_no_profile", null);
            return Result.NO_PROFILE;
        }

        if (plugin.configManager().isRejectAlreadyRegistered() && hasRegistration(target)) {
            send(player, "registration.fail_already_registered", null);
            return Result.ALREADY_REGISTERED;
        }

        int maxPerPlayer = plugin.configManager().getMaxVehiclesPerPlayer();
        if (maxPerPlayer > 0
                && !player.hasPermission(OwnershipService.BYPASS_PERMISSION)) {
            int current = index.byOwner(player.getUniqueId()).size();
            if (current >= maxPerPlayer) {
                Map<String, String> p = new HashMap<>();
                p.put("max", String.valueOf(maxPerPlayer));
                p.put("current", String.valueOf(current));
                send(player, "registration.fail_max_vehicles", p);
                return Result.MAX_REACHED;
            }
        }

        if (profile.requiresTamedOwner() && target instanceof Tameable tameable) {
            if (!tameable.isTamed()) {
                send(player, "registration.fail_not_tamed", null);
                return Result.NOT_TAMED;
            }
            if (tameable.getOwner() == null
                    || !tameable.getOwner().getUniqueId().equals(player.getUniqueId())) {
                send(player, "registration.fail_not_owner", null);
                return Result.NOT_OWNER;
            }
        }

        if (profile.requiresSaddle()
                && (!(target instanceof Steerable steerable) || !steerable.hasSaddle())) {
            send(player, "registration.fail_requires_saddle", null);
            return Result.REQUIRES_SADDLE;
        }

        if (plugin.configManager().isRequireEmptyVehicle()
                && (target instanceof Vehicle || target instanceof AbstractHorse)
                && !target.getPassengers().isEmpty()) {
            send(player, "registration.fail_not_empty", null);
            return Result.NOT_EMPTY;
        }

        double cost = plugin.configManager().getRegisterCost();
        boolean charged = false;
        if (cost > 0 && economy().isReady()) {
            if (!economy().has(player, cost)) {
                Map<String, String> p = new HashMap<>();
                p.put("amount", formatMoney(cost));
                send(player, "registration.fail_economy", p);
                return Result.NOT_ENOUGH_MONEY;
            }
            if (!economy().withdraw(player, cost)) {
                Map<String, String> p = new HashMap<>();
                p.put("amount", formatMoney(cost));
                send(player, "registration.fail_economy", p);
                return Result.NOT_ENOUGH_MONEY;
            }
            charged = true;
            Map<String, String> p = new HashMap<>();
            p.put("amount", formatMoney(cost));
            send(player, "registration.charged", p);
        }

        UUID vehicleId = UUID.randomUUID();
        VehicleRecord record;
        try {
            record = writeAndIndex(player, target, profile, vehicleId);
            if (handItem.getAmount() > 1) {
                handItem.setAmount(handItem.getAmount() - 1);
            } else {
                player.getInventory().setItemInMainHand(null);
            }
        } catch (RuntimeException ex) {
            rollbackRegistration(target, vehicleId);
            if (charged && !economy().deposit(player, cost)) {
                plugin.getLogger().warning("Registration failed after charging "
                        + player.getName() + "; automatic refund of " + formatMoney(cost) + " failed.");
            }
            cooldowns.clear(player.getUniqueId());
            plugin.getLogger().log(Level.SEVERE, "Failed to register vehicle " + vehicleId, ex);
            send(player, "registration.fail_internal", null);
            return Result.FAILED;
        }

        Map<String, String> p = new HashMap<>();
        p.put("profile", profile.id());
        p.put("vehicle_id", record.shortId());
        send(player, "registration.success", p);

        return Result.SUCCESS;
    }

    private VehicleRecord writeAndIndex(Player player, Entity target, VehicleProfile profile, UUID vehicleId) {
        long now = System.currentTimeMillis();
        String plate = generatePlate();

        PersistentDataContainer pdc = target.getPersistentDataContainer();
        pdc.set(keys.vehicleId(), PersistentDataType.STRING, vehicleId.toString());
        pdc.set(keys.ownerUuid(), PersistentDataType.STRING, player.getUniqueId().toString());
        pdc.set(keys.profile(), PersistentDataType.STRING, profile.id());
        pdc.set(keys.state(), PersistentDataType.STRING, "ACTIVE");
        pdc.set(keys.createdAt(), PersistentDataType.LONG, now);
        pdc.set(keys.schemaVersion(), PersistentDataType.INTEGER, PdcKeys.SCHEMA_VERSION);

        VehicleRecord record = new VehicleRecord(vehicleId, target.getUniqueId(),
                target.getType().name(), player.getUniqueId(), profile.id());
        record.setPlate(plate);
        Location loc = target.getLocation();
        if (loc.getWorld() != null) {
            record.setLocation(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                    loc.getYaw(), loc.getPitch());
        }
        record.setCreatedAt(now);
        record.setLastSeenAt(now);

        applyDisplayName(record, target, player.getName());

        if (target instanceof LivingEntity living) {
            living.setRemoveWhenFarAway(false);
        }
        target.setPersistent(true);
        target.addScoreboardTag("mountlicense_registered");

        index.put(record);

        if (plugin.configManager().isDebug()) {
            plugin.getLogger().info("Registered vehicle " + vehicleId + " (" + profile.id()
                    + ") for " + player.getName());
        }
        return record;
    }

    public int refreshLoadedDisplayNames() {
        int refreshed = 0;
        for (VehicleRecord record : index.all()) {
            Entity entity = Bukkit.getEntity(record.entityUuid());
            if (entity == null) continue;

            String ownerName = Bukkit.getOfflinePlayer(record.ownerUuid()).getName();
            applyDisplayName(record, entity, ownerName == null ? "" : ownerName);
            refreshed++;
        }
        if (refreshed > 0) {
            index.markDirty();
        }
        return refreshed;
    }

    private void applyDisplayName(VehicleRecord record, Entity target, String ownerName) {
        String format = plugin.configManager().getDisplayNameFormat();
        if (format == null || format.isEmpty()) return;

        String name = format.replace("%player%", ownerName == null ? "" : ownerName)
                .replace("%profile%", record.profile())
                .replace("%plate%", record.plate());
        target.setCustomName(name);
        target.setCustomNameVisible(false);
        record.setDisplayName(name);
    }

    private String generatePlate() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String plate = randomPlate();
            boolean taken = false;
            for (VehicleRecord rec : index.all()) {
                if (plate.equalsIgnoreCase(rec.plate())) {
                    taken = true;
                    break;
                }
            }
            if (!taken) return plate;
        }
        return randomPlate();
    }

    private String randomPlate() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder out = new StringBuilder(7);
        for (int i = 0; i < 3; i++) {
            out.append(PLATE_LETTERS.charAt(random.nextInt(PLATE_LETTERS.length())));
        }
        out.append('-');
        out.append(random.nextInt(10));
        out.append(random.nextInt(10));
        out.append(random.nextInt(10));
        return out.toString();
    }

    private void rollbackRegistration(Entity target, UUID vehicleId) {
        if (target != null) {
            PersistentDataContainer pdc = target.getPersistentDataContainer();
            pdc.remove(keys.vehicleId());
            pdc.remove(keys.ownerUuid());
            pdc.remove(keys.profile());
            pdc.remove(keys.state());
            pdc.remove(keys.createdAt());
            pdc.remove(keys.schemaVersion());
            target.removeScoreboardTag("mountlicense_registered");
        }
        if (vehicleId != null) {
            index.remove(vehicleId);
        }
    }

    public boolean hasRegistration(Entity entity) {
        if (entity == null) return false;
        return entity.getPersistentDataContainer().has(keys.vehicleId(), PersistentDataType.STRING);
    }

    public UUID readVehicleId(Entity entity) {
        if (entity == null) return null;
        String raw = entity.getPersistentDataContainer().get(keys.vehicleId(), PersistentDataType.STRING);
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public ReindexResult reindexLoadedEntities() {
        int scanned = 0;
        int recovered = 0;
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                scanned++;
                UUID vid = readVehicleId(e);
                if (vid == null) continue;
                if (index.byId(vid) != null) continue;

                String ownerRaw = e.getPersistentDataContainer()
                        .get(keys.ownerUuid(), PersistentDataType.STRING);
                String profileId = e.getPersistentDataContainer()
                        .get(keys.profile(), PersistentDataType.STRING);
                Long createdAt = e.getPersistentDataContainer()
                        .get(keys.createdAt(), PersistentDataType.LONG);
                if (ownerRaw == null || profileId == null) continue;

                try {
                    UUID owner = UUID.fromString(ownerRaw);
                    VehicleRecord rec = new VehicleRecord(vid, e.getUniqueId(),
                            e.getType().name(), owner, profileId);
                    if (createdAt != null) rec.setCreatedAt(createdAt);
                    rec.setLastSeenAt(System.currentTimeMillis());
                    Location loc = e.getLocation();
                    if (loc.getWorld() != null) {
                        rec.setLocation(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                                loc.getYaw(), loc.getPitch());
                    }
                    index.put(rec);
                    recovered++;
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Bad owner UUID on entity " + e.getUniqueId() + ": " + ownerRaw);
                }
            }
        }
        return new ReindexResult(scanned, recovered);
    }

    private String formatMoney(double amount) {
        return String.format("%.2f", amount);
    }

    private void send(Player player, String key, Map<String, String> placeholders) {
        lang.send(player, key, placeholders);
    }
}
