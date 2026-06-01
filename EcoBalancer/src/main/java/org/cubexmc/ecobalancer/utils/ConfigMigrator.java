package org.cubexmc.ecobalancer.utils;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.ecobalancer.EcoBalancer;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Logger;

/**
 * Handles automatic migration of config.yml and language files
 * when the plugin is updated to a new version.
 */
public class ConfigMigrator {
    private final EcoBalancer plugin;
    private final Logger logger;

    // Increment these when config structure changes
    public static final int CURRENT_CONFIG_VERSION = 4;
    public static final int CURRENT_LANG_VERSION = 3;

    public ConfigMigrator(EcoBalancer plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Check and migrate config.yml if needed
     * 
     * @return true if migration occurred
     */
    public boolean migrateConfig() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");

        // If config doesn't exist, let Bukkit create it fresh
        if (!configFile.exists()) {
            return false;
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        int currentVersion = config.getInt("config-version", 1);

        if (currentVersion >= CURRENT_CONFIG_VERSION) {
            return false; // Already up to date
        }

        logger.info("Migrating config.yml from version " + currentVersion + " to " + CURRENT_CONFIG_VERSION);

        // Create backup before migration
        createBackup(configFile, "config");

        // Load default config from jar
        FileConfiguration defaultConfig = loadDefaultConfig();
        if (defaultConfig == null) {
            logger.warning("Could not load default config for migration");
            return false;
        }

        // Perform migration steps
        // Apply version-specific migrations
        if (currentVersion < 2) {
            if (migrateConfigV1ToV2(config, defaultConfig)) {
                logger.info("Applied V1 -> V2 migration");
            }
        }
        if (currentVersion < 3) {
            if (migrateConfigV2ToV3(config)) {
                logger.info("Applied V2 -> V3 migration (Policy Extraction)");
            }
        }
        if (currentVersion < 4) {
            if (migrateConfigV3ToV4(config, defaultConfig)) {
                logger.info("Applied V3 -> V4 migration (Tax safety and ledger defaults)");
            }
        }

        // Merge any new keys from default config
        mergeNewKeys(config, defaultConfig, "");

        // Update version number
        config.set("config-version", CURRENT_CONFIG_VERSION);

        // Save migrated config
        try {
            config.save(configFile);
            logger.info("Config migration completed successfully");
            return true;
        } catch (IOException e) {
            logger.severe("Failed to save migrated config: " + e.getMessage());
            return false;
        }
    }

    private boolean migrateConfigV3ToV4(FileConfiguration config, FileConfiguration defaults) {
        boolean changed = false;
        String[] keys = {
                "tax-exempt.enabled",
                "tax-exempt.global-permission",
                "tax-exempt.policy-permission-prefix",
                "tax-exempt.operation-permission-prefix",
                "debt-mode",
                "debt-commands"
        };
        for (String key : keys) {
            if (!config.contains(key) && defaults.contains(key)) {
                config.set(key, defaults.get(key));
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Migrate config from v1 to v2
     */
    private boolean migrateConfigV1ToV2(FileConfiguration config, FileConfiguration defaults) {
        boolean changed = false;

        // Add new options introduced in v2
        String[] newKeys = {
                "max-deduction-per-player",
                "min-balance-protection",
                "require-confirmation",
                "tax-exempt-permission"
        };

        for (String key : newKeys) {
            if (!config.contains(key) && defaults.contains(key)) {
                config.set(key, defaults.get(key));
                logger.info("Added new config option: " + key);
                changed = true;
            }
        }

        return changed;
    }

    /**
     * Migrate config from v2 to v3: Extract tax settings to policies/default.yml
     */
    private boolean migrateConfigV2ToV3(FileConfiguration config) {
        File policiesDir = new File(plugin.getDataFolder(), "policies");
        if (!policiesDir.exists()) {
            policiesDir.mkdirs();
        }

        File defaultPolicyFile = new File(policiesDir, "default.yml");
        if (defaultPolicyFile.exists()) {
            logger.info("Policy file already exists, skipping default policy creation.");
            // We still proceed to clean up legacy config if strictly following migration,
            // but safer to assume if policy exists we might have migrated before or user
            // did.
            // However, to ensure cleanup, we should continue.
        }

        // Create TaxPolicy config object
        // Create "policies" map structure
        YamlConfiguration policyConfig = new YamlConfiguration();
        ConfigurationSection policiesSection = policyConfig.createSection("policies");

        // --- Policy 1: Default (Migrated) ---
        // Routine = true
        ConfigurationSection defaultPolicy = policiesSection.createSection("default");
        defaultPolicy.set("description", "Migrated from legacy config");
        defaultPolicy.set("routine", true);
        // Schedule section
        // Legacy format used a 'check-schedule' section
        String type = "monthly";
        List<Integer> daysOfWeek = new ArrayList<>();
        List<Integer> datesOfMonth = new ArrayList<>();

        if (config.isConfigurationSection("check-schedule")) {
            ConfigurationSection scheduleSec = config.getConfigurationSection("check-schedule");
            type = scheduleSec.getString("type", "monthly");
            daysOfWeek = scheduleSec.getIntegerList("days-of-week");
            datesOfMonth = scheduleSec.getIntegerList("dates-of-month");
        } else {
            // Fallback for very old configs or unexpected structure
            type = config.getString("schedule-type", "monthly");
            daysOfWeek = config.getIntegerList("schedule-days-of-week");
            datesOfMonth = config.getIntegerList("schedule-dates-of-month");
        }

        defaultPolicy.set("schedule.type", type);
        defaultPolicy.set("schedule.time", config.getString("check-time", "00:00"));
        defaultPolicy.set("schedule.days-of-week", daysOfWeek);
        defaultPolicy.set("schedule.dates-of-month", datesOfMonth);

        // Settings section
        // These keys seem to be flat in the user's provided config excerpt
        // except possibly 'inactive-days-to-*' being conditioned on
        // 'deduct-based-on-time'
        // for now we migrate values if present.

        defaultPolicy.set("settings.max-deduction", config.getDouble("max-deduction-per-player", 0.0));
        // Legacy config might not have min-balance-protection or it might be new
        defaultPolicy.set("settings.min-balance-protection", config.getDouble("min-balance-protection", 0.0));

        boolean onlyOffline = config.getBoolean("only-offline-players", false);
        // User config shows 'deduct-based-on-time' which might be related, but let's
        // stick to keys we see
        defaultPolicy.set("settings.only-offline", onlyOffline);

        defaultPolicy.set("settings.inactive-days-deduct", config.getInt("inactive-days-to-deduct", 0));
        defaultPolicy.set("settings.inactive-days-clear", config.getInt("inactive-days-to-clear", 0));
        defaultPolicy.set("settings.percentile", config.getBoolean("percentile-thresholds", false)); // If exists
        defaultPolicy.set("settings.exempt-permission", "");
        defaultPolicy.set("settings.debt-mode", "inherit");

        // Brackets
        List<Map<String, Object>> brackets = new ArrayList<>();
        if (config.contains("tax-brackets")) {
            List<Map<?, ?>> rawBrackets = config.getMapList("tax-brackets");
            for (Map<?, ?> raw : rawBrackets) {
                Map<String, Object> bracket = new HashMap<>();

                // Handle threshold: null cases which map to "no limit"
                // Our TaxPolicy logic handles MAX_VALUE as effectively no limit for upper
                // bounds if sorted
                // But let's check what TaxPolicy expects. Usually threshold is the upper bound
                // of a bracket?
                // Or the start?
                // "threshold: 100000 rate: 0.001" likely means up to 100k? Or starting at 100k?
                // The provided config shows progressive steps: 100k, 1m, null.
                // This implies:
                // 0 -> 100k : 0.001? Or >100k?
                // A common progressive tax pattern is:
                // [0, 100k) -> rate 1
                // [100k, 1m) -> rate 2
                // [1m, inf) -> rate 3
                // We will preserve the 'threshold' value directly. If it is null in YAML,
                // get("threshold") returns null.

                Object th = raw.get("threshold");
                if (th == null) {
                    // Treat null as unlimited -> Max Value
                    bracket.put("threshold", Integer.MAX_VALUE);
                } else if (th instanceof Number) {
                    bracket.put("threshold", ((Number) th).intValue());
                } else {
                    // Try parsing string "null" or others
                    String thStr = String.valueOf(th);
                    if ("null".equalsIgnoreCase(thStr)) {
                        bracket.put("threshold", Integer.MAX_VALUE);
                    } else {
                        try {
                            bracket.put("threshold", Integer.parseInt(thStr));
                        } catch (NumberFormatException e) {
                            bracket.put("threshold", Integer.MAX_VALUE);
                        }
                    }
                }

                if (raw.containsKey("rate")) {
                    bracket.put("rate", raw.get("rate"));
                } else {
                    bracket.put("rate", 0.0);
                }
                brackets.add(bracket);
            }
        } else {
            Map<String, Object> bracket = new HashMap<>();
            bracket.put("threshold", 1000.0);
            bracket.put("rate", 0.05);
            brackets.add(bracket);
        }
        defaultPolicy.set("brackets", brackets);

        // --- Policy 2: Manual Check Example ---
        ConfigurationSection manualPolicy = policiesSection.createSection("manual_check");
        manualPolicy.set("description", "A Manual execution policy example");
        manualPolicy.set("routine", false);
        manualPolicy.set("settings.max-deduction", 500.0);
        List<Map<String, Object>> manBrackets = new ArrayList<>();
        Map<String, Object> mb = new HashMap<>();
        mb.put("threshold", 0.0);
        mb.put("rate", 0.02);
        manBrackets.add(mb);
        manualPolicy.set("brackets", manBrackets);

        // Save default policy
        try {
            if (!defaultPolicyFile.exists()) {
                policyConfig.save(defaultPolicyFile);
                logger.info("Created policies/default.yml from legacy config.");
            }
        } catch (IOException e) {
            logger.severe("Failed to save default policy: " + e.getMessage());
            return false;
        }

        // Remove legacy keys from main config
        String[] keysToRemove = {
                "schedule-type",
                // "check-time", // KEEP for snapshot!
                "schedule-days-of-week",
                "schedule-dates-of-month",
                "inactive-days-to-deduct",
                "inactive-days-to-clear",
                "max-deduction-per-player",
                "min-balance-protection",
                "only-offline-players",
                "percentile-thresholds",
                "tax-brackets",
                "tax-exempt-permission" // Removed feature or moved to per-policy (not implemented yet, just remove
                                        // legacy global)
        };

        for (String key : keysToRemove) {
            config.set(key, null);
        }

        return true;
    }

    /**
     * Check and migrate language file if needed
     * 
     * @param langCode e.g., "en_US" or "zh_CN"
     * @return true if migration occurred
     */
    public boolean migrateLanguageFile(String langCode) {
        File langFile = new File(plugin.getDataFolder(), "lang" + File.separator + langCode + ".yml");

        // If language file doesn't exist, let the plugin create it fresh
        if (!langFile.exists()) {
            return false;
        }

        FileConfiguration langConfig = YamlConfiguration.loadConfiguration(langFile);
        int currentVersion = langConfig.getInt("lang-version", 1);

        if (currentVersion >= CURRENT_LANG_VERSION) {
            return false; // Already up to date
        }

        logger.info("Migrating language file '" + langCode + "' from version " + currentVersion + " to "
                + CURRENT_LANG_VERSION);

        // Create backup before migration
        createBackup(langFile, "lang_" + langCode);

        // Load default language file from jar
        FileConfiguration defaultLang = loadDefaultLanguageFile(langCode);
        if (defaultLang == null) {
            logger.warning("Could not load default language file for migration: " + langCode);
            return false;
        }

        // Merge new keys from default
        mergeNewKeys(langConfig, defaultLang, "");

        // Update version number
        langConfig.set("lang-version", CURRENT_LANG_VERSION);

        // Save migrated language file
        try {
            langConfig.save(langFile);
            logger.info("Language file migration completed successfully: " + langCode);
            return true;
        } catch (IOException e) {
            logger.severe("Failed to save migrated language file: " + e.getMessage());
            return false;
        }
    }

    /**
     * Create a timestamped backup of a file before migration
     */
    public void createBackup(File file, String prefix) {
        if (!file.exists()) {
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd-HHmmss");
        String timestamp = sdf.format(new Date());
        File backupFile = new File(file.getParent(), prefix + ".bak." + timestamp);

        try {
            Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.info("Created backup: " + backupFile.getName());
        } catch (IOException e) {
            logger.warning("Failed to create backup: " + e.getMessage());
        }
    }

    /**
     * Recursively merge new keys from default config into existing config
     * 
     * @return true if any keys were added
     */
    private boolean mergeNewKeys(FileConfiguration existing, FileConfiguration defaults, String path) {
        boolean added = false;

        ConfigurationSection defaultSection = path.isEmpty() ? defaults : defaults.getConfigurationSection(path);
        if (defaultSection == null) {
            return false;
        }

        for (String key : defaultSection.getKeys(false)) {
            String fullKey = path.isEmpty() ? key : path + "." + key;

            // Skip version keys - they're handled separately
            if (key.equals("config-version") || key.equals("lang-version")) {
                continue;
            }

            Object defaultValue = defaults.get(fullKey);

            if (!existing.contains(fullKey)) {
                // Key doesn't exist in current config, add it
                existing.set(fullKey, defaultValue);
                logger.info("Added missing key: " + fullKey);
                added = true;
            } else if (defaultValue instanceof ConfigurationSection) {
                // Recursively check nested sections
                added |= mergeNewKeys(existing, defaults, fullKey);
            }
            // If key exists and is not a section, preserve the user's value
        }

        return added;
    }

    /**
     * Load the default config.yml from the jar
     */
    private FileConfiguration loadDefaultConfig() {
        try (InputStream is = plugin.getResource("config.yml")) {
            if (is == null) {
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(is)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            logger.warning("Error loading default config: " + e.getMessage());
            return null;
        }
    }

    /**
     * Load a default language file from the jar
     */
    private FileConfiguration loadDefaultLanguageFile(String langCode) {
        String resourcePath = "lang/" + langCode + ".yml";
        try (InputStream is = plugin.getResource(resourcePath)) {
            if (is == null) {
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(is)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            logger.warning("Error loading default language file: " + e.getMessage());
            return null;
        }
    }

    /**
     * Check if config migration is needed
     */
    public boolean isConfigMigrationNeeded() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            return false;
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        int currentVersion = config.getInt("config-version", 1);
        return currentVersion < CURRENT_CONFIG_VERSION;
    }

    /**
     * Check if language file migration is needed
     */
    public boolean isLangMigrationNeeded(String langCode) {
        File langFile = new File(plugin.getDataFolder(), "lang" + File.separator + langCode + ".yml");
        if (!langFile.exists()) {
            return false;
        }
        FileConfiguration langConfig = YamlConfiguration.loadConfiguration(langFile);
        int currentVersion = langConfig.getInt("lang-version", 1);
        return currentVersion < CURRENT_LANG_VERSION;
    }

    /**
     * Get the current config version from file
     */
    public int getCurrentConfigVersion() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            return 0;
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        return config.getInt("config-version", 1);
    }

    /**
     * Get the current language file version
     */
    public int getCurrentLangVersion(String langCode) {
        File langFile = new File(plugin.getDataFolder(), "lang" + File.separator + langCode + ".yml");
        if (!langFile.exists()) {
            return 0;
        }
        FileConfiguration langConfig = YamlConfiguration.loadConfiguration(langFile);
        return langConfig.getInt("lang-version", 1);
    }
}
