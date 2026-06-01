package org.cubexmc.mountlicense.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.cubexmc.mountlicense.MountLicensePlugin;

public class ConfigManager {

    private static final String LEGACY_DISPLAY_NAME_FORMAT = "%player%'s %profile%";

    private final MountLicensePlugin plugin;

    private String language = "zh_CN";
    private boolean economyEnabled = true;
    private double registerCost = 0.0;
    private double recallCost = 0.0;

    private Material licenseMaterial = Material.PAPER;
    private int licenseCustomModelData = 0;
    private String licenseDisplayKey = "license_item.display";
    private String licenseLoreKey = "license_item.lore";

    private Material keyMaterial = Material.CARROT_ON_A_STICK;
    private int keyCustomModelData = 0;
    private String keyDisplayKey = "key_item.display";
    private String keyLoreKey = "key_item.lore";

    private double recallSearchRadius = 100.0;
    private int recallCooldownSeconds = 30;
    private boolean recallRequireSafeDestination = true;
    private boolean recallWakeOnRecall = true;

    private boolean requireTamed = true;
    private boolean requireEmptyVehicle = true;
    private boolean rejectAlreadyRegistered = true;
    private double maxInteractDistance = 6.0;
    private String displayNameFormat = "%plate%";
    private int maxVehiclesPerPlayer = -1;

    public enum ParkMode { AUTO, MANUAL }
    private ParkMode parkMode = ParkMode.AUTO;

    private int maxTrusteesPerVehicle = -1;

    private int registerCooldownSeconds = 2;
    private int keyUseCooldownSeconds = 3;
    private int parkCooldownSeconds = 1;

    private boolean protectMount = true;
    private boolean protectDamage = true;
    private boolean protectDestroy = true;
    private boolean protectInventory = true;
    private boolean protectLeash = true;
    private boolean notifyBlocked = true;
    private long notifyDebounceMs = 1500;
    private boolean cleanupOnDeath = true;

    private int autosaveIntervalTicks = 1200;
    private boolean flushOnDisable = true;

    private boolean debug = false;

    public ConfigManager(MountLicensePlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        language = cfg.getString("language", "zh_CN");

        economyEnabled = cfg.getBoolean("economy.enabled", true);
        registerCost = cfg.getDouble("economy.register_cost", 0.0);
        recallCost = cfg.getDouble("economy.recall_cost", 0.0);

        licenseMaterial = parseMaterial(cfg.getString("license_item.material", "PAPER"), Material.PAPER);
        licenseCustomModelData = cfg.getInt("license_item.custom_model_data", 0);
        licenseDisplayKey = cfg.getString("license_item.display_name", "license_item.display");
        licenseLoreKey = cfg.getString("license_item.lore_key", "license_item.lore");

        keyMaterial = parseMaterial(cfg.getString("key_item.material", "CARROT_ON_A_STICK"),
                Material.CARROT_ON_A_STICK);
        keyCustomModelData = cfg.getInt("key_item.custom_model_data", 0);
        keyDisplayKey = cfg.getString("key_item.display_name", "key_item.display");
        keyLoreKey = cfg.getString("key_item.lore_key", "key_item.lore");

        recallSearchRadius = cfg.getDouble("recall.search_radius", 100.0);
        recallCooldownSeconds = cfg.getInt("recall.cooldown_seconds", 30);
        recallRequireSafeDestination = cfg.getBoolean("recall.require_safe_destination", true);
        recallWakeOnRecall = cfg.getBoolean("recall.wake_on_recall", true);

        requireTamed = cfg.getBoolean("registration.require_tamed", true);
        requireEmptyVehicle = cfg.getBoolean("registration.require_empty_vehicle", true);
        rejectAlreadyRegistered = cfg.getBoolean("registration.reject_already_registered", true);
        maxInteractDistance = cfg.getDouble("registration.max_interact_distance", 6.0);
        displayNameFormat = cfg.getString("registration.display_name_format", "%plate%");
        if (LEGACY_DISPLAY_NAME_FORMAT.equals(displayNameFormat)) {
            displayNameFormat = "%plate%";
        }
        maxVehiclesPerPlayer = cfg.getInt("registration.max_vehicles_per_player", -1);

        String parkRaw = cfg.getString("park_mode", "auto");
        try {
            parkMode = ParkMode.valueOf(parkRaw.toUpperCase());
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Unknown park_mode '" + parkRaw + "', falling back to AUTO.");
            parkMode = ParkMode.AUTO;
        }

        maxTrusteesPerVehicle = cfg.getInt("trust.max_trustees_per_vehicle", -1);

        registerCooldownSeconds = cfg.getInt("cooldowns.register", 2);
        keyUseCooldownSeconds = cfg.getInt("cooldowns.key_use", 3);
        parkCooldownSeconds = cfg.getInt("cooldowns.park", 1);

        protectMount = cfg.getBoolean("protection.block_player_mount", true);
        protectDamage = cfg.getBoolean("protection.block_player_damage", true);
        protectDestroy = cfg.getBoolean("protection.block_player_destroy", true);
        protectInventory = cfg.getBoolean("protection.block_inventory_access", true);
        protectLeash = cfg.getBoolean("protection.block_leash", true);
        notifyBlocked = cfg.getBoolean("protection.notify_blocked", true);
        notifyDebounceMs = cfg.getLong("protection.notify_debounce_ms", 1500L);
        cleanupOnDeath = cfg.getBoolean("protection.cleanup_on_death", true);

        autosaveIntervalTicks = cfg.getInt("persistence.autosave_interval_ticks", 1200);
        flushOnDisable = cfg.getBoolean("persistence.flush_on_disable", true);

        debug = cfg.getBoolean("debug", false);
    }

    private Material parseMaterial(String raw, Material fallback) {
        if (raw == null) return fallback;
        Material m = Material.matchMaterial(raw);
        return m != null ? m : fallback;
    }

    public String getLanguage() { return language; }
    public boolean isEconomyEnabled() { return economyEnabled; }
    public double getRegisterCost() { return registerCost; }
    public double getRecallCost() { return recallCost; }

    public Material getLicenseMaterial() { return licenseMaterial; }
    public int getLicenseCustomModelData() { return licenseCustomModelData; }
    public String getLicenseDisplayKey() { return licenseDisplayKey; }
    public String getLicenseLoreKey() { return licenseLoreKey; }

    public Material getKeyMaterial() { return keyMaterial; }
    public int getKeyCustomModelData() { return keyCustomModelData; }
    public String getKeyDisplayKey() { return keyDisplayKey; }
    public String getKeyLoreKey() { return keyLoreKey; }

    public double getRecallSearchRadius() { return recallSearchRadius; }
    public int getRecallCooldownSeconds() { return recallCooldownSeconds; }
    public boolean isRecallRequireSafeDestination() { return recallRequireSafeDestination; }
    public boolean isRecallWakeOnRecall() { return recallWakeOnRecall; }

    public boolean isRequireTamed() { return requireTamed; }
    public boolean isRequireEmptyVehicle() { return requireEmptyVehicle; }
    public boolean isRejectAlreadyRegistered() { return rejectAlreadyRegistered; }
    public double getMaxInteractDistance() { return maxInteractDistance; }
    public String getDisplayNameFormat() { return displayNameFormat; }
    public int getMaxVehiclesPerPlayer() { return maxVehiclesPerPlayer; }
    public ParkMode getParkMode() { return parkMode; }
    public int getMaxTrusteesPerVehicle() { return maxTrusteesPerVehicle; }

    public int getRegisterCooldownSeconds() { return registerCooldownSeconds; }
    public int getKeyUseCooldownSeconds() { return keyUseCooldownSeconds; }
    public int getParkCooldownSeconds() { return parkCooldownSeconds; }

    public boolean isProtectMount() { return protectMount; }
    public boolean isProtectDamage() { return protectDamage; }
    public boolean isProtectDestroy() { return protectDestroy; }
    public boolean isProtectInventory() { return protectInventory; }
    public boolean isProtectLeash() { return protectLeash; }
    public boolean isNotifyBlocked() { return notifyBlocked; }
    public long getNotifyDebounceMs() { return notifyDebounceMs; }
    public boolean isCleanupOnDeath() { return cleanupOnDeath; }

    public int getAutosaveIntervalTicks() { return autosaveIntervalTicks; }
    public boolean isFlushOnDisable() { return flushOnDisable; }

    public boolean isDebug() { return debug; }
}
