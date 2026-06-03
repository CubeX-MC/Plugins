package org.cubexmc.ecobalancer.utils;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.config.LegacyTextToMiniMessageStep;
import org.cubexmc.config.MigrationException;
import org.cubexmc.config.MigrationPlan;
import org.cubexmc.config.MigrationReport;
import org.cubexmc.config.MigrationRunner;
import org.cubexmc.config.MigrationStep;
import org.cubexmc.config.NoOpMigrationStep;
import org.cubexmc.ecobalancer.EcoBalancer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Handles automatic migration of config.yml and language files
 * when the plugin is updated to a new version.
 */
public class ConfigMigrator {
    private static final int LEGACY_CONFIG_VERSION = 4;
    private static final int LEGACY_LANG_VERSION = 3;

    private final EcoBalancer plugin;
    private final Logger logger;

    // Increment these when config structure changes
    public static final int CURRENT_CONFIG_VERSION = 5;
    public static final int CURRENT_LANG_VERSION = 4;

    public ConfigMigrator(EcoBalancer plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Check and migrate config.yml if needed.
     *
     * @return true if migration occurred
     */
    public boolean migrateConfig() {
        try {
            return migrateConfigOrThrow();
        } catch (MigrationException e) {
            logger.severe("Config migration failed: " + e.getMessage());
            return false;
        }
    }

    public boolean migrateConfigOrThrow() throws MigrationException {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            return false;
        }
        MigrationReport report = new MigrationRunner(plugin).run(configPlan());
        return report.migrated();
    }

    /**
     * Check and migrate language file if needed.
     *
     * @param langCode e.g., "en_US" or "zh_CN"
     * @return true if migration occurred
     */
    public boolean migrateLanguageFile(String langCode) {
        try {
            return migrateLanguageFileOrThrow(langCode);
        } catch (MigrationException e) {
            logger.severe("Language migration failed for " + langCode + ": " + e.getMessage());
            return false;
        }
    }

    public boolean migrateLanguageFileOrThrow(String langCode) throws MigrationException {
        File langFile = new File(plugin.getDataFolder(), "lang" + File.separator + langCode + ".yml");
        if (!langFile.exists()) {
            return false;
        }
        MigrationReport report = new MigrationRunner(plugin).run(languagePlan(langCode));
        return report.migrated();
    }

    public void migrateStartupFilesOrThrow(String langCode) throws MigrationException {
        if (migrateConfigOrThrow()) {
            logger.info("Configuration migrated to version " + CURRENT_CONFIG_VERSION);
        }
        if (migrateLanguageFileOrThrow(langCode)) {
            logger.info("Language file '" + langCode + "' migrated to version " + CURRENT_LANG_VERSION);
        }
    }

    /**
     * Create a timestamped backup of a file before manual migration backup commands.
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

    private MigrationPlan configPlan() {
        return MigrationPlan.yaml("EcoBalancer config", "config.yml")
                .versionKey("config-version")
                .missingVersion(1)
                .targetVersion(CURRENT_CONFIG_VERSION)
                .addStep(new LegacyConfigMigrationStep(1))
                .addStep(new LegacyConfigMigrationStep(2))
                .addStep(new LegacyConfigMigrationStep(3))
                .addStep(new NoOpMigrationStep(LEGACY_CONFIG_VERSION, CURRENT_CONFIG_VERSION,
                        "Reserve v5 for MiniMessage-era config without changing command DSL values."));
    }

    private MigrationPlan languagePlan(String langCode) {
        String resourcePath = "lang/" + langCode + ".yml";
        return MigrationPlan.yaml("EcoBalancer language " + langCode, resourcePath)
                .versionKey("lang-version")
                .missingVersion(1)
                .targetVersion(CURRENT_LANG_VERSION)
                .addStep(new ModernizeLanguageStep(1, resourcePath))
                .addStep(new ModernizeLanguageStep(2, resourcePath))
                .addStep(new ModernizeLanguageStep(3, resourcePath));
    }

    private final class LegacyConfigMigrationStep implements MigrationStep {
        private final int fromVersion;

        private LegacyConfigMigrationStep(int fromVersion) {
            this.fromVersion = fromVersion;
        }

        @Override
        public int fromVersion() {
            return fromVersion;
        }

        @Override
        public int toVersion() {
            return LEGACY_CONFIG_VERSION;
        }

        @Override
        public String description() {
            return "Reuse EcoBalancer legacy config migration v" + fromVersion + " -> v" + LEGACY_CONFIG_VERSION + ".";
        }

        @Override
        public void migrate(org.cubexmc.config.MigrationContext context) {
            FileConfiguration defaults = loadDefaultConfig();
            if (defaults == null) {
                context.fail("config.yml", "Could not load default config for migration.");
                return;
            }
            applyLegacyConfigMigration(context.yaml(), defaults, fromVersion);
        }
    }

    private final class ModernizeLanguageStep implements MigrationStep {
        private final int fromVersion;
        private final String resourcePath;
        private final LegacyTextToMiniMessageStep converter;

        private ModernizeLanguageStep(int fromVersion, String resourcePath) {
            this.fromVersion = fromVersion;
            this.resourcePath = resourcePath;
            this.converter = new LegacyTextToMiniMessageStep(fromVersion, CURRENT_LANG_VERSION);
        }

        @Override
        public int fromVersion() {
            return fromVersion;
        }

        @Override
        public int toVersion() {
            return CURRENT_LANG_VERSION;
        }

        @Override
        public String description() {
            return "Convert existing legacy language strings first, then merge current MiniMessage defaults.";
        }

        @Override
        public void migrate(org.cubexmc.config.MigrationContext context) {
            convertExistingStrings(context.yaml(), "");
            FileConfiguration defaults = loadDefaultResource(resourcePath);
            if (defaults == null) {
                context.fail(resourcePath, "Could not load bundled MiniMessage defaults.");
                return;
            }
            mergeNewKeys(context.yaml(), defaults, "");
        }

        private void convertExistingStrings(ConfigurationSection section, String basePath) {
            for (String key : section.getKeys(false)) {
                String path = basePath.isEmpty() ? key : basePath + "." + key;
                if (section.isConfigurationSection(key)) {
                    convertExistingStrings(section.getConfigurationSection(key), path);
                } else if (section.isString(key)) {
                    section.set(key, convertLegacy(section.getString(key, "")));
                } else if (section.isList(key)) {
                    List<?> values = section.getList(key);
                    if (values != null && values.stream().allMatch(value -> value instanceof String)) {
                        section.set(key, values.stream()
                                .map(value -> convertLegacy((String) value))
                                .toList());
                    }
                }
            }
        }

        private String convertLegacy(String value) {
            return converter.convert(value == null ? "" : value.replace('§', '&'));
        }
    }

    void applyLegacyConfigMigration(FileConfiguration config, FileConfiguration defaultConfig, int currentVersion) {
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
        mergeNewKeys(config, defaultConfig, "");
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
     * Migrate config from v1 to v2.
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
     * Migrate config from v2 to v3: Extract tax settings to policies/default.yml.
     */
    private boolean migrateConfigV2ToV3(FileConfiguration config) {
        File policiesDir = new File(plugin.getDataFolder(), "policies");
        if (!policiesDir.exists()) {
            policiesDir.mkdirs();
        }

        File defaultPolicyFile = new File(policiesDir, "default.yml");
        if (defaultPolicyFile.exists()) {
            logger.info("Policy file already exists, skipping default policy creation.");
        }

        YamlConfiguration policyConfig = new YamlConfiguration();
        ConfigurationSection policiesSection = policyConfig.createSection("policies");

        ConfigurationSection defaultPolicy = policiesSection.createSection("default");
        defaultPolicy.set("description", "Migrated from legacy config");
        defaultPolicy.set("routine", true);

        String type = "monthly";
        List<Integer> daysOfWeek = new ArrayList<>();
        List<Integer> datesOfMonth = new ArrayList<>();

        if (config.isConfigurationSection("check-schedule")) {
            ConfigurationSection scheduleSec = config.getConfigurationSection("check-schedule");
            type = scheduleSec.getString("type", "monthly");
            daysOfWeek = scheduleSec.getIntegerList("days-of-week");
            datesOfMonth = scheduleSec.getIntegerList("dates-of-month");
        } else {
            type = config.getString("schedule-type", "monthly");
            daysOfWeek = config.getIntegerList("schedule-days-of-week");
            datesOfMonth = config.getIntegerList("schedule-dates-of-month");
        }

        defaultPolicy.set("schedule.type", type);
        defaultPolicy.set("schedule.time", config.getString("check-time", "00:00"));
        defaultPolicy.set("schedule.days-of-week", daysOfWeek);
        defaultPolicy.set("schedule.dates-of-month", datesOfMonth);

        defaultPolicy.set("settings.max-deduction", config.getDouble("max-deduction-per-player", 0.0));
        defaultPolicy.set("settings.min-balance-protection", config.getDouble("min-balance-protection", 0.0));

        boolean onlyOffline = config.getBoolean("only-offline-players", false);
        defaultPolicy.set("settings.only-offline", onlyOffline);

        defaultPolicy.set("settings.inactive-days-deduct", config.getInt("inactive-days-to-deduct", 0));
        defaultPolicy.set("settings.inactive-days-clear", config.getInt("inactive-days-to-clear", 0));
        defaultPolicy.set("settings.percentile", config.getBoolean("percentile-thresholds", false));
        defaultPolicy.set("settings.exempt-permission", "");
        defaultPolicy.set("settings.debt-mode", "inherit");

        List<Map<String, Object>> brackets = new ArrayList<>();
        if (config.contains("tax-brackets")) {
            List<Map<?, ?>> rawBrackets = config.getMapList("tax-brackets");
            for (Map<?, ?> raw : rawBrackets) {
                Map<String, Object> bracket = new HashMap<>();

                Object th = raw.get("threshold");
                if (th == null) {
                    bracket.put("threshold", Integer.MAX_VALUE);
                } else if (th instanceof Number) {
                    bracket.put("threshold", ((Number) th).intValue());
                } else {
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

        try {
            if (!defaultPolicyFile.exists()) {
                policyConfig.save(defaultPolicyFile);
                logger.info("Created policies/default.yml from legacy config.");
            }
        } catch (IOException e) {
            logger.severe("Failed to save default policy: " + e.getMessage());
            return false;
        }

        String[] keysToRemove = {
                "schedule-type",
                "schedule-days-of-week",
                "schedule-dates-of-month",
                "inactive-days-to-deduct",
                "inactive-days-to-clear",
                "max-deduction-per-player",
                "min-balance-protection",
                "only-offline-players",
                "percentile-thresholds",
                "tax-brackets",
                "tax-exempt-permission"
        };

        for (String key : keysToRemove) {
            config.set(key, null);
        }

        return true;
    }

    /**
     * Recursively merge new keys from default config into existing config.
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

            if (key.equals("config-version") || key.equals("lang-version")) {
                continue;
            }

            Object defaultValue = defaults.get(fullKey);

            if (!existing.contains(fullKey)) {
                existing.set(fullKey, defaultValue);
                logger.info("Added missing key: " + fullKey);
                added = true;
            } else if (defaultValue instanceof ConfigurationSection) {
                added |= mergeNewKeys(existing, defaults, fullKey);
            }
        }

        return added;
    }

    /**
     * Load the default config.yml from the jar.
     */
    private FileConfiguration loadDefaultConfig() {
        return loadDefaultResource("config.yml");
    }

    /**
     * Load a default language file from the jar.
     */
    private FileConfiguration loadDefaultLanguageFile(String langCode) {
        return loadDefaultResource("lang/" + langCode + ".yml");
    }

    private FileConfiguration loadDefaultResource(String resourcePath) {
        try (InputStream is = plugin.getResource(resourcePath)) {
            if (is == null) {
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            logger.warning("Error loading default resource " + resourcePath + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Check if config migration is needed.
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
     * Check if language file migration is needed.
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
     * Get the current config version from file.
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
     * Get the current language file version.
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
