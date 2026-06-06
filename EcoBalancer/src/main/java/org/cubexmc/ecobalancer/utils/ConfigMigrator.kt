package org.cubexmc.ecobalancer.utils

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.config.LegacyTextToMiniMessageStep
import org.cubexmc.config.MigrationContext
import org.cubexmc.config.MigrationException
import org.cubexmc.config.MigrationPlan
import org.cubexmc.config.MigrationRunner
import org.cubexmc.config.MigrationStep
import org.cubexmc.config.NoOpMigrationStep
import org.cubexmc.ecobalancer.EcoBalancer
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.logging.Logger

class ConfigMigrator(private val plugin: EcoBalancer) {
    private val logger: Logger = plugin.logger

    fun migrateConfig(): Boolean =
        try {
            migrateConfigOrThrow()
        } catch (exception: MigrationException) {
            logger.severe("Config migration failed: ${exception.message}")
            false
        }

    @Throws(MigrationException::class)
    fun migrateConfigOrThrow(): Boolean {
        val configFile = File(plugin.dataFolder, "config.yml")
        if (!configFile.exists()) {
            return false
        }
        val report = MigrationRunner(plugin).run(configPlan())
        return report.migrated()
    }

    fun migrateLanguageFile(langCode: String): Boolean =
        try {
            migrateLanguageFileOrThrow(langCode)
        } catch (exception: MigrationException) {
            logger.severe("Language migration failed for $langCode: ${exception.message}")
            false
        }

    @Throws(MigrationException::class)
    fun migrateLanguageFileOrThrow(langCode: String): Boolean {
        val langFile = File(plugin.dataFolder, "lang" + File.separator + langCode + ".yml")
        if (!langFile.exists()) {
            return false
        }
        val report = MigrationRunner(plugin).run(languagePlan(langCode))
        return report.migrated()
    }

    @Throws(MigrationException::class)
    fun migrateStartupFilesOrThrow(langCode: String) {
        if (migrateConfigOrThrow()) {
            logger.info("Configuration migrated to version $CURRENT_CONFIG_VERSION")
        }
        if (migrateLanguageFileOrThrow(langCode)) {
            logger.info("Language file '$langCode' migrated to version $CURRENT_LANG_VERSION")
        }
    }

    fun createBackup(file: File, prefix: String) {
        if (!file.exists()) {
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        val backupFile = File(file.parent, "$prefix.bak.$timestamp")

        try {
            Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            logger.info("Created backup: ${backupFile.name}")
        } catch (exception: IOException) {
            logger.warning("Failed to create backup: ${exception.message}")
        }
    }

    private fun configPlan(): MigrationPlan =
        MigrationPlan.yaml("EcoBalancer config", "config.yml")
            .versionKey("config-version")
            .missingVersion(1)
            .targetVersion(CURRENT_CONFIG_VERSION)
            .addStep(LegacyConfigMigrationStep(1))
            .addStep(LegacyConfigMigrationStep(2))
            .addStep(LegacyConfigMigrationStep(3))
            .addStep(
                NoOpMigrationStep(
                    LEGACY_CONFIG_VERSION,
                    CURRENT_CONFIG_VERSION,
                    "Reserve v5 for MiniMessage-era config without changing command DSL values.",
                ),
            )

    private fun languagePlan(langCode: String): MigrationPlan {
        val resourcePath = "lang/$langCode.yml"
        return MigrationPlan.yaml("EcoBalancer language $langCode", resourcePath)
            .versionKey("lang-version")
            .missingVersion(1)
            .targetVersion(CURRENT_LANG_VERSION)
            .addStep(ModernizeLanguageStep(1, resourcePath))
            .addStep(ModernizeLanguageStep(2, resourcePath))
            .addStep(ModernizeLanguageStep(3, resourcePath))
    }

    private inner class LegacyConfigMigrationStep(private val fromVersionValue: Int) : MigrationStep {
        override fun fromVersion(): Int = fromVersionValue

        override fun toVersion(): Int = LEGACY_CONFIG_VERSION

        override fun description(): String =
            "Reuse EcoBalancer legacy config migration v$fromVersionValue -> v$LEGACY_CONFIG_VERSION."

        override fun migrate(context: MigrationContext) {
            val defaults = loadDefaultConfig()
            if (defaults == null) {
                context.fail("config.yml", "Could not load default config for migration.")
                return
            }
            applyLegacyConfigMigration(context.yaml(), defaults, fromVersionValue)
        }
    }

    private inner class ModernizeLanguageStep(
        private val fromVersionValue: Int,
        private val resourcePath: String,
    ) : MigrationStep {
        private val converter = LegacyTextToMiniMessageStep(fromVersionValue, CURRENT_LANG_VERSION)

        override fun fromVersion(): Int = fromVersionValue

        override fun toVersion(): Int = CURRENT_LANG_VERSION

        override fun description(): String =
            "Convert existing legacy language strings first, then merge current MiniMessage defaults."

        override fun migrate(context: MigrationContext) {
            convertExistingStrings(context.yaml(), "")
            val defaults = loadDefaultResource(resourcePath)
            if (defaults == null) {
                context.fail(resourcePath, "Could not load bundled MiniMessage defaults.")
                return
            }
            mergeNewKeys(context.yaml(), defaults, "")
        }

        private fun convertExistingStrings(section: ConfigurationSection, basePath: String) {
            for (key in section.getKeys(false)) {
                val path = if (basePath.isEmpty()) key else "$basePath.$key"
                if (section.isConfigurationSection(key)) {
                    val child = section.getConfigurationSection(key)
                    if (child != null) {
                        convertExistingStrings(child, path)
                    }
                } else if (section.isString(key)) {
                    section.set(key, convertLegacy(section.getString(key, "")))
                } else if (section.isList(key)) {
                    val values = section.getList(key)
                    if (values != null && values.all { it is String }) {
                        section.set(key, values.map { convertLegacy(it as String) })
                    }
                }
            }
        }

        private fun convertLegacy(value: String?): String = converter.convert((value ?: "").replace('§', '&'))
    }

    fun applyLegacyConfigMigration(config: FileConfiguration, defaultConfig: FileConfiguration, currentVersion: Int) {
        if (currentVersion < 2) {
            if (migrateConfigV1ToV2(config, defaultConfig)) {
                logger.info("Applied V1 -> V2 migration")
            }
        }
        if (currentVersion < 3) {
            if (migrateConfigV2ToV3(config)) {
                logger.info("Applied V2 -> V3 migration (Policy Extraction)")
            }
        }
        if (currentVersion < 4) {
            if (migrateConfigV3ToV4(config, defaultConfig)) {
                logger.info("Applied V3 -> V4 migration (Tax safety and ledger defaults)")
            }
        }
        mergeNewKeys(config, defaultConfig, "")
    }

    private fun migrateConfigV3ToV4(config: FileConfiguration, defaults: FileConfiguration): Boolean {
        var changed = false
        val keys = arrayOf(
            "tax-exempt.enabled",
            "tax-exempt.global-permission",
            "tax-exempt.policy-permission-prefix",
            "tax-exempt.operation-permission-prefix",
            "debt-mode",
            "debt-commands",
        )
        for (key in keys) {
            if (!config.contains(key) && defaults.contains(key)) {
                config.set(key, defaults.get(key))
                changed = true
            }
        }
        return changed
    }

    private fun migrateConfigV1ToV2(config: FileConfiguration, defaults: FileConfiguration): Boolean {
        var changed = false
        val newKeys = arrayOf(
            "max-deduction-per-player",
            "min-balance-protection",
            "require-confirmation",
            "tax-exempt-permission",
        )

        for (key in newKeys) {
            if (!config.contains(key) && defaults.contains(key)) {
                config.set(key, defaults.get(key))
                logger.info("Added new config option: $key")
                changed = true
            }
        }

        return changed
    }

    private fun migrateConfigV2ToV3(config: FileConfiguration): Boolean {
        val policiesDir = File(plugin.dataFolder, "policies")
        if (!policiesDir.exists()) {
            policiesDir.mkdirs()
        }

        val defaultPolicyFile = File(policiesDir, "default.yml")
        if (defaultPolicyFile.exists()) {
            logger.info("Policy file already exists, skipping default policy creation.")
        }

        val policyConfig = YamlConfiguration()
        val policiesSection = policyConfig.createSection("policies")

        val defaultPolicy = policiesSection.createSection("default")
        defaultPolicy.set("description", "Migrated from legacy config")
        defaultPolicy.set("routine", true)

        var type = "monthly"
        var daysOfWeek: List<Int> = ArrayList()
        var datesOfMonth: List<Int> = ArrayList()

        if (config.isConfigurationSection("check-schedule")) {
            val scheduleSec = config.getConfigurationSection("check-schedule")
            if (scheduleSec != null) {
                type = scheduleSec.getString("type", "monthly") ?: "monthly"
                daysOfWeek = scheduleSec.getIntegerList("days-of-week")
                datesOfMonth = scheduleSec.getIntegerList("dates-of-month")
            }
        } else {
            type = config.getString("schedule-type", "monthly") ?: "monthly"
            daysOfWeek = config.getIntegerList("schedule-days-of-week")
            datesOfMonth = config.getIntegerList("schedule-dates-of-month")
        }

        defaultPolicy.set("schedule.type", type)
        defaultPolicy.set("schedule.time", config.getString("check-time", "00:00"))
        defaultPolicy.set("schedule.days-of-week", daysOfWeek)
        defaultPolicy.set("schedule.dates-of-month", datesOfMonth)
        defaultPolicy.set("settings.max-deduction", config.getDouble("max-deduction-per-player", 0.0))
        defaultPolicy.set("settings.min-balance-protection", config.getDouble("min-balance-protection", 0.0))
        defaultPolicy.set("settings.only-offline", config.getBoolean("only-offline-players", false))
        defaultPolicy.set("settings.inactive-days-deduct", config.getInt("inactive-days-to-deduct", 0))
        defaultPolicy.set("settings.inactive-days-clear", config.getInt("inactive-days-to-clear", 0))
        defaultPolicy.set("settings.percentile", config.getBoolean("percentile-thresholds", false))
        defaultPolicy.set("settings.exempt-permission", "")
        defaultPolicy.set("settings.debt-mode", "inherit")

        val brackets: MutableList<Map<String, Any>> = ArrayList()
        if (config.contains("tax-brackets")) {
            val rawBrackets = config.getMapList("tax-brackets")
            for (raw in rawBrackets) {
                val bracket: MutableMap<String, Any> = HashMap()

                val th = raw["threshold"]
                if (th == null) {
                    bracket["threshold"] = Int.MAX_VALUE
                } else if (th is Number) {
                    bracket["threshold"] = th.toInt()
                } else {
                    val thStr = th.toString()
                    if ("null".equals(thStr, ignoreCase = true)) {
                        bracket["threshold"] = Int.MAX_VALUE
                    } else {
                        try {
                            bracket["threshold"] = thStr.toInt()
                        } catch (_: NumberFormatException) {
                            bracket["threshold"] = Int.MAX_VALUE
                        }
                    }
                }

                if (raw.containsKey("rate")) {
                    raw["rate"]?.let { bracket["rate"] = it }
                } else {
                    bracket["rate"] = 0.0
                }
                brackets.add(bracket)
            }
        } else {
            val bracket: MutableMap<String, Any> = HashMap()
            bracket["threshold"] = 1000.0
            bracket["rate"] = 0.05
            brackets.add(bracket)
        }
        defaultPolicy.set("brackets", brackets)

        val manualPolicy = policiesSection.createSection("manual_check")
        manualPolicy.set("description", "A Manual execution policy example")
        manualPolicy.set("routine", false)
        manualPolicy.set("settings.max-deduction", 500.0)
        val manBrackets: MutableList<Map<String, Any>> = ArrayList()
        val mb: MutableMap<String, Any> = HashMap()
        mb["threshold"] = 0.0
        mb["rate"] = 0.02
        manBrackets.add(mb)
        manualPolicy.set("brackets", manBrackets)

        try {
            if (!defaultPolicyFile.exists()) {
                policyConfig.save(defaultPolicyFile)
                logger.info("Created policies/default.yml from legacy config.")
            }
        } catch (exception: IOException) {
            logger.severe("Failed to save default policy: ${exception.message}")
            return false
        }

        val keysToRemove = arrayOf(
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
            "tax-exempt-permission",
        )

        for (key in keysToRemove) {
            config.set(key, null)
        }

        return true
    }

    private fun mergeNewKeys(existing: FileConfiguration, defaults: FileConfiguration, path: String): Boolean {
        var added = false

        val defaultSection = if (path.isEmpty()) defaults else defaults.getConfigurationSection(path)
        if (defaultSection == null) {
            return false
        }

        for (key in defaultSection.getKeys(false)) {
            val fullKey = if (path.isEmpty()) key else "$path.$key"

            if (key == "config-version" || key == "lang-version") {
                continue
            }

            val defaultValue = defaults.get(fullKey)

            if (!existing.contains(fullKey)) {
                existing.set(fullKey, defaultValue)
                logger.info("Added missing key: $fullKey")
                added = true
            } else if (defaultValue is ConfigurationSection) {
                added = mergeNewKeys(existing, defaults, fullKey) || added
            }
        }

        return added
    }

    private fun loadDefaultConfig(): FileConfiguration? = loadDefaultResource("config.yml")

    @Suppress("unused")
    private fun loadDefaultLanguageFile(langCode: String): FileConfiguration? = loadDefaultResource("lang/$langCode.yml")

    private fun loadDefaultResource(resourcePath: String): FileConfiguration? =
        try {
            val stream = plugin.getResource(resourcePath) ?: return null
            stream.use { input ->
                InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                    YamlConfiguration.loadConfiguration(reader)
                }
            }
        } catch (exception: IOException) {
            logger.warning("Error loading default resource $resourcePath: ${exception.message}")
            null
        }

    fun isConfigMigrationNeeded(): Boolean {
        val configFile = File(plugin.dataFolder, "config.yml")
        if (!configFile.exists()) {
            return false
        }
        val config = YamlConfiguration.loadConfiguration(configFile)
        val currentVersion = config.getInt("config-version", 1)
        return currentVersion < CURRENT_CONFIG_VERSION
    }

    fun isLangMigrationNeeded(langCode: String): Boolean {
        val langFile = File(plugin.dataFolder, "lang" + File.separator + langCode + ".yml")
        if (!langFile.exists()) {
            return false
        }
        val langConfig = YamlConfiguration.loadConfiguration(langFile)
        val currentVersion = langConfig.getInt("lang-version", 1)
        return currentVersion < CURRENT_LANG_VERSION
    }

    fun getCurrentConfigVersion(): Int {
        val configFile = File(plugin.dataFolder, "config.yml")
        if (!configFile.exists()) {
            return 0
        }
        val config = YamlConfiguration.loadConfiguration(configFile)
        return config.getInt("config-version", 1)
    }

    fun getCurrentLangVersion(langCode: String): Int {
        val langFile = File(plugin.dataFolder, "lang" + File.separator + langCode + ".yml")
        if (!langFile.exists()) {
            return 0
        }
        val langConfig = YamlConfiguration.loadConfiguration(langFile)
        return langConfig.getInt("lang-version", 1)
    }

    companion object {
        private const val LEGACY_CONFIG_VERSION = 4
        private const val LEGACY_LANG_VERSION = 3

        @JvmField
        val CURRENT_CONFIG_VERSION: Int = 5

        @JvmField
        val CURRENT_LANG_VERSION: Int = 4
    }
}
