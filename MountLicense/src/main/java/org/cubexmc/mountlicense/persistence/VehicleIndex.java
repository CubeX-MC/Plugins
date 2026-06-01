package org.cubexmc.mountlicense.persistence;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;
import org.cubexmc.mountlicense.MountLicensePlugin;
import org.cubexmc.mountlicense.model.VehicleRecord;
import org.cubexmc.mountlicense.model.VehicleState;

public class VehicleIndex {

    private static final int SCHEMA = 1;
    private static final String FILE_NAME = "vehicles.yml";

    private final MountLicensePlugin plugin;
    private final File file;
    private final Map<UUID, VehicleRecord> byVehicleId = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> entityToVehicle = new ConcurrentHashMap<>();
    private final Map<UUID, List<UUID>> ownerToVehicles = new ConcurrentHashMap<>();

    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private BukkitTask autosaveTask;

    public VehicleIndex(MountLicensePlugin plugin) {
        this(plugin, new File(plugin.getDataFolder(), FILE_NAME));
    }

    VehicleIndex(MountLicensePlugin plugin, File file) {
        this.plugin = plugin;
        this.file = file;
    }

    public void load() {
        byVehicleId.clear();
        entityToVehicle.clear();
        ownerToVehicles.clear();

        if (!file.exists()) {
            startAutosave();
            return;
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("vehicles");
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    VehicleRecord rec = readRecord(UUID.fromString(key), s);
                    indexInternal(rec);
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Skipping vehicle entry with bad UUID: " + key);
                }
            }
        }

        plugin.getLogger().info("Loaded " + byVehicleId.size() + " vehicle record(s).");
        startAutosave();
    }

    private VehicleRecord readRecord(UUID id, ConfigurationSection s) {
        UUID entityUuid = UUID.fromString(s.getString("entityUuid", new UUID(0, 0).toString()));
        String entityType = s.getString("entityType", "UNKNOWN");
        UUID ownerUuid = UUID.fromString(s.getString("ownerUuid", new UUID(0, 0).toString()));
        String profile = s.getString("profile", "");

        VehicleRecord rec = new VehicleRecord(id, entityUuid, entityType, ownerUuid, profile);
        rec.setPlate(s.getString("plate"));
        rec.setDisplayName(s.getString("displayName"));
        rec.setState(VehicleState.fromString(s.getString("state"), VehicleState.ACTIVE));
        rec.setStationId(s.getString("stationId"));

        if (s.contains("world")) {
            rec.setLocation(
                    s.getString("world"),
                    s.getDouble("x"),
                    s.getDouble("y"),
                    s.getDouble("z"),
                    (float) s.getDouble("yaw"),
                    (float) s.getDouble("pitch")
            );
        }

        if (s.contains("createdAt")) rec.setCreatedAt(s.getLong("createdAt"));
        if (s.contains("lastSeenAt")) rec.setLastSeenAt(s.getLong("lastSeenAt"));

        for (String raw : s.getStringList("trustees")) {
            try {
                rec.addTrustee(UUID.fromString(raw));
            } catch (IllegalArgumentException ex) {
                // skip malformed trustee uuid
            }
        }
        return rec;
    }

    private void indexInternal(VehicleRecord rec) {
        byVehicleId.put(rec.vehicleId(), rec);
        entityToVehicle.put(rec.entityUuid(), rec.vehicleId());
        ownerToVehicles.computeIfAbsent(rec.ownerUuid(), k -> Collections.synchronizedList(new ArrayList<>()))
                .add(rec.vehicleId());
    }

    public synchronized void put(VehicleRecord rec) {
        VehicleRecord existing = byVehicleId.get(rec.vehicleId());
        if (existing != null) {
            entityToVehicle.remove(existing.entityUuid());
            List<UUID> ownerList = ownerToVehicles.get(existing.ownerUuid());
            if (ownerList != null) ownerList.remove(existing.vehicleId());
        }
        indexInternal(rec);
        markDirty();
    }

    public synchronized VehicleRecord remove(UUID vehicleId) {
        VehicleRecord rec = byVehicleId.remove(vehicleId);
        if (rec != null) {
            entityToVehicle.remove(rec.entityUuid());
            List<UUID> ownerList = ownerToVehicles.get(rec.ownerUuid());
            if (ownerList != null) ownerList.remove(vehicleId);
            markDirty();
        }
        return rec;
    }

    public VehicleRecord byId(UUID vehicleId) {
        return byVehicleId.get(vehicleId);
    }

    public VehicleRecord byEntity(UUID entityUuid) {
        UUID vehicleId = entityToVehicle.get(entityUuid);
        return vehicleId == null ? null : byVehicleId.get(vehicleId);
    }

    public List<VehicleRecord> byOwner(UUID ownerUuid) {
        List<UUID> ids = ownerToVehicles.get(ownerUuid);
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        List<VehicleRecord> out = new ArrayList<>(ids.size());
        synchronized (ids) {
            for (UUID id : ids) {
                VehicleRecord rec = byVehicleId.get(id);
                if (rec != null) out.add(rec);
            }
        }
        return out;
    }

    public Collection<VehicleRecord> all() {
        return Collections.unmodifiableCollection(byVehicleId.values());
    }

    public int size() {
        return byVehicleId.size();
    }

    public void markDirty() {
        dirty.set(true);
    }

    public void flush() {
        if (!dirty.getAndSet(false)) return;
        saveBlocking();
    }

    private void saveBlocking() {
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Cannot create data folder for vehicle index.");
                return;
            }
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("schema", SCHEMA);
            Map<String, Map<String, Object>> dump = new HashMap<>();
            for (VehicleRecord rec : byVehicleId.values()) {
                dump.put(rec.vehicleId().toString(), writeRecord(rec));
            }
            cfg.createSection("vehicles", dump);
            cfg.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save vehicles.yml", ex);
            dirty.set(true);
        }
    }

    private Map<String, Object> writeRecord(VehicleRecord rec) {
        Map<String, Object> m = new HashMap<>();
        m.put("entityUuid", rec.entityUuid().toString());
        m.put("entityType", rec.entityType());
        m.put("ownerUuid", rec.ownerUuid().toString());
        m.put("profile", rec.profile());
        m.put("plate", rec.plate());
        if (rec.displayName() != null) m.put("displayName", rec.displayName());
        m.put("state", rec.state().name());
        if (rec.stationId() != null) m.put("stationId", rec.stationId());
        if (rec.world() != null) {
            m.put("world", rec.world());
            m.put("x", rec.x());
            m.put("y", rec.y());
            m.put("z", rec.z());
            m.put("yaw", rec.yaw());
            m.put("pitch", rec.pitch());
        }
        m.put("createdAt", rec.createdAt());
        m.put("lastSeenAt", rec.lastSeenAt());

        if (!rec.trustees().isEmpty()) {
            List<String> trusteeStrings = new ArrayList<>();
            synchronized (rec.trustees()) {
                for (UUID t : rec.trustees()) {
                    trusteeStrings.add(t.toString());
                }
            }
            m.put("trustees", trusteeStrings);
        }
        return m;
    }

    private void startAutosave() {
        int interval = plugin.configManager().getAutosaveIntervalTicks();
        if (interval <= 0) return;
        if (autosaveTask != null) autosaveTask.cancel();
        autosaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (dirty.getAndSet(false)) {
                saveBlocking();
            }
        }, interval, interval);
    }
}
