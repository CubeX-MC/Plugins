package org.cubexmc.mountlicense.config

import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import org.cubexmc.mountlicense.MountLicensePlugin
import java.util.Locale

class ConfigManager(private val plugin: MountLicensePlugin) {
    private var language = "zh_CN"
    private var economyEnabled = true
    private var registerCost = 0.0
    private var recallCost = 0.0

    private var licenseMaterial = Material.PAPER
    private var licenseCustomModelData = 0
    private var licenseDisplayKey = "license_item.display"
    private var licenseLoreKey = "license_item.lore"

    private var keyMaterial = Material.CARROT_ON_A_STICK
    private var keyCustomModelData = 0
    private var keyDisplayKey = "key_item.display"
    private var keyLoreKey = "key_item.lore"

    private var recallSearchRadius = 100.0
    private var recallCooldownSeconds = 30
    private var recallRequireSafeDestination = true
    private var recallWakeOnRecall = true

    private var requireTamed = true
    private var requireEmptyVehicle = true
    private var rejectAlreadyRegistered = true
    private var maxInteractDistance = 6.0
    private var displayNameFormat = "%plate%"
    private var maxVehiclesPerPlayer = -1

    enum class ParkMode { AUTO, MANUAL }

    private var parkMode = ParkMode.AUTO
    private var maxTrusteesPerVehicle = -1

    private var registerCooldownSeconds = 2
    private var keyUseCooldownSeconds = 3
    private var parkCooldownSeconds = 1

    private var protectMount = true
    private var protectDamage = true
    private var protectDestroy = true
    private var protectInventory = true
    private var protectLeash = true
    private var notifyBlocked = true
    private var notifyDebounceMs = 1500L
    private var cleanupOnDeath = true

    private var autosaveIntervalTicks = 1200
    private var flushOnDisable = true
    private var debug = false

    fun load() {
        plugin.reloadConfig()
        val cfg: FileConfiguration = plugin.config

        language = cfg.getString("language", "zh_CN") ?: "zh_CN"

        economyEnabled = cfg.getBoolean("economy.enabled", true)
        registerCost = cfg.getDouble("economy.register_cost", 0.0)
        recallCost = cfg.getDouble("economy.recall_cost", 0.0)

        licenseMaterial = parseMaterial(cfg.getString("license_item.material", "PAPER"), Material.PAPER)
        licenseCustomModelData = cfg.getInt("license_item.custom_model_data", 0)
        licenseDisplayKey = cfg.getString("license_item.display_name", "license_item.display")
            ?: "license_item.display"
        licenseLoreKey = cfg.getString("license_item.lore_key", "license_item.lore") ?: "license_item.lore"

        keyMaterial = parseMaterial(
            cfg.getString("key_item.material", "CARROT_ON_A_STICK"),
            Material.CARROT_ON_A_STICK,
        )
        keyCustomModelData = cfg.getInt("key_item.custom_model_data", 0)
        keyDisplayKey = cfg.getString("key_item.display_name", "key_item.display") ?: "key_item.display"
        keyLoreKey = cfg.getString("key_item.lore_key", "key_item.lore") ?: "key_item.lore"

        recallSearchRadius = cfg.getDouble("recall.search_radius", 100.0)
        recallCooldownSeconds = cfg.getInt("recall.cooldown_seconds", 30)
        recallRequireSafeDestination = cfg.getBoolean("recall.require_safe_destination", true)
        recallWakeOnRecall = cfg.getBoolean("recall.wake_on_recall", true)

        requireTamed = cfg.getBoolean("registration.require_tamed", true)
        requireEmptyVehicle = cfg.getBoolean("registration.require_empty_vehicle", true)
        rejectAlreadyRegistered = cfg.getBoolean("registration.reject_already_registered", true)
        maxInteractDistance = cfg.getDouble("registration.max_interact_distance", 6.0)
        displayNameFormat = cfg.getString("registration.display_name_format", "%plate%") ?: "%plate%"
        if (LEGACY_DISPLAY_NAME_FORMAT == displayNameFormat) {
            displayNameFormat = "%plate%"
        }
        maxVehiclesPerPlayer = cfg.getInt("registration.max_vehicles_per_player", -1)

        val parkRaw = cfg.getString("park_mode", "auto") ?: "auto"
        parkMode = try {
            ParkMode.valueOf(parkRaw.uppercase(Locale.getDefault()))
        } catch (ex: IllegalArgumentException) {
            plugin.logger.warning("Unknown park_mode '$parkRaw', falling back to AUTO.")
            ParkMode.AUTO
        }

        maxTrusteesPerVehicle = cfg.getInt("trust.max_trustees_per_vehicle", -1)

        registerCooldownSeconds = cfg.getInt("cooldowns.register", 2)
        keyUseCooldownSeconds = cfg.getInt("cooldowns.key_use", 3)
        parkCooldownSeconds = cfg.getInt("cooldowns.park", 1)

        protectMount = cfg.getBoolean("protection.block_player_mount", true)
        protectDamage = cfg.getBoolean("protection.block_player_damage", true)
        protectDestroy = cfg.getBoolean("protection.block_player_destroy", true)
        protectInventory = cfg.getBoolean("protection.block_inventory_access", true)
        protectLeash = cfg.getBoolean("protection.block_leash", true)
        notifyBlocked = cfg.getBoolean("protection.notify_blocked", true)
        notifyDebounceMs = cfg.getLong("protection.notify_debounce_ms", 1500L)
        cleanupOnDeath = cfg.getBoolean("protection.cleanup_on_death", true)

        autosaveIntervalTicks = cfg.getInt("persistence.autosave_interval_ticks", 1200)
        flushOnDisable = cfg.getBoolean("persistence.flush_on_disable", true)
        debug = cfg.getBoolean("debug", false)
    }

    private fun parseMaterial(raw: String?, fallback: Material): Material {
        if (raw == null) return fallback
        return Material.matchMaterial(raw) ?: fallback
    }

    fun getLanguage(): String = language
    fun isEconomyEnabled(): Boolean = economyEnabled
    fun getRegisterCost(): Double = registerCost
    fun getRecallCost(): Double = recallCost

    fun getLicenseMaterial(): Material = licenseMaterial
    fun getLicenseCustomModelData(): Int = licenseCustomModelData
    fun getLicenseDisplayKey(): String = licenseDisplayKey
    fun getLicenseLoreKey(): String = licenseLoreKey

    fun getKeyMaterial(): Material = keyMaterial
    fun getKeyCustomModelData(): Int = keyCustomModelData
    fun getKeyDisplayKey(): String = keyDisplayKey
    fun getKeyLoreKey(): String = keyLoreKey

    fun getRecallSearchRadius(): Double = recallSearchRadius
    fun getRecallCooldownSeconds(): Int = recallCooldownSeconds
    fun isRecallRequireSafeDestination(): Boolean = recallRequireSafeDestination
    fun isRecallWakeOnRecall(): Boolean = recallWakeOnRecall

    fun isRequireTamed(): Boolean = requireTamed
    fun isRequireEmptyVehicle(): Boolean = requireEmptyVehicle
    fun isRejectAlreadyRegistered(): Boolean = rejectAlreadyRegistered
    fun getMaxInteractDistance(): Double = maxInteractDistance
    fun getDisplayNameFormat(): String = displayNameFormat
    fun getMaxVehiclesPerPlayer(): Int = maxVehiclesPerPlayer
    fun getParkMode(): ParkMode = parkMode
    fun getMaxTrusteesPerVehicle(): Int = maxTrusteesPerVehicle

    fun getRegisterCooldownSeconds(): Int = registerCooldownSeconds
    fun getKeyUseCooldownSeconds(): Int = keyUseCooldownSeconds
    fun getParkCooldownSeconds(): Int = parkCooldownSeconds

    fun isProtectMount(): Boolean = protectMount
    fun isProtectDamage(): Boolean = protectDamage
    fun isProtectDestroy(): Boolean = protectDestroy
    fun isProtectInventory(): Boolean = protectInventory
    fun isProtectLeash(): Boolean = protectLeash
    fun isNotifyBlocked(): Boolean = notifyBlocked
    fun getNotifyDebounceMs(): Long = notifyDebounceMs
    fun isCleanupOnDeath(): Boolean = cleanupOnDeath

    fun getAutosaveIntervalTicks(): Int = autosaveIntervalTicks
    fun isFlushOnDisable(): Boolean = flushOnDisable
    fun isDebug(): Boolean = debug

    private companion object {
        const val LEGACY_DISPLAY_NAME_FORMAT: String = "%player%'s %profile%"
    }
}
