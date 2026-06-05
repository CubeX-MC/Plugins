package org.cubexmc.mountlicense

import org.bukkit.command.PluginCommand
import org.cubexmc.config.LegacyTextToMiniMessageStep
import org.cubexmc.config.MigrationException
import org.cubexmc.config.MigrationPlan
import org.cubexmc.config.MigrationRunner
import org.cubexmc.config.NoOpMigrationStep
import org.cubexmc.config.ResourceFiles
import org.cubexmc.core.CubexPlugin
import org.cubexmc.mountlicense.command.MountLicenseCommand
import org.cubexmc.mountlicense.config.ConfigManager
import org.cubexmc.mountlicense.config.ProfileRegistry
import org.cubexmc.mountlicense.lang.LanguageManager
import org.cubexmc.mountlicense.listener.AutoParkListener
import org.cubexmc.mountlicense.listener.KeyItemListener
import org.cubexmc.mountlicense.listener.LicenseHintListener
import org.cubexmc.mountlicense.listener.ProtectionListener
import org.cubexmc.mountlicense.listener.RegistrationListener
import org.cubexmc.mountlicense.persistence.VehicleIndex
import org.cubexmc.mountlicense.service.ItemFactory
import org.cubexmc.mountlicense.service.OwnershipService
import org.cubexmc.mountlicense.service.ParkingService
import org.cubexmc.mountlicense.service.PdcKeys
import org.cubexmc.mountlicense.service.RecallService
import org.cubexmc.mountlicense.service.RegistryService

class MountLicensePlugin : CubexPlugin() {
    private lateinit var configManagerField: ConfigManager
    private lateinit var languageManagerField: LanguageManager
    private lateinit var profileRegistryField: ProfileRegistry
    private lateinit var pdcKeysField: PdcKeys
    private lateinit var vehicleIndexField: VehicleIndex
    private lateinit var itemFactoryField: ItemFactory
    private lateinit var ownershipServiceField: OwnershipService
    private lateinit var parkingServiceField: ParkingService
    private lateinit var registryServiceField: RegistryService
    private lateinit var recallServiceField: RecallService
    private lateinit var resourceFiles: ResourceFiles

    override fun enablePlugin() {
        resourceFiles = ResourceFiles(this)
        saveDefaultResources()
        try {
            migrateConfigAndLang()
        } catch (ex: MigrationException) {
            logger.severe("MountLicense enable aborted: migration failed. ${ex.message}")
            abortEnable("MountLicense migration failed. See logs for details.")
        }

        configManagerField = ConfigManager(this)
        configManagerField.load()

        languageManagerField = LanguageManager(this, configManagerField.getLanguage())
        languageManagerField.load()

        profileRegistryField = ProfileRegistry(this)
        profileRegistryField.load()

        pdcKeysField = PdcKeys(this)
        vehicleIndexField = VehicleIndex(this)
        vehicleIndexField.load()
        bind(Runnable {
            if (::vehicleIndexField.isInitialized) {
                vehicleIndexField.flush()
            }
        })

        itemFactoryField = ItemFactory(this, pdcKeysField, languageManagerField)
        ownershipServiceField = OwnershipService(pdcKeysField, vehicleIndexField)
        parkingServiceField = ParkingService(this, pdcKeysField, vehicleIndexField, ownershipServiceField, languageManagerField)
        registryServiceField = RegistryService(this, pdcKeysField, vehicleIndexField, profileRegistryField, languageManagerField)
        recallServiceField = RecallService(this, ownershipServiceField, profileRegistryField, vehicleIndexField, languageManagerField)
        registryServiceField.refreshLoadedDisplayNames()

        server.pluginManager.registerEvents(RegistrationListener(this, registryServiceField, pdcKeysField), this)
        server.pluginManager.registerEvents(LicenseHintListener(this, itemFactoryField, languageManagerField), this)
        server.pluginManager.registerEvents(ProtectionListener(this, ownershipServiceField, languageManagerField), this)
        server.pluginManager.registerEvents(AutoParkListener(this, ownershipServiceField, parkingServiceField), this)
        server.pluginManager.registerEvents(
            KeyItemListener(this, itemFactoryField, ownershipServiceField, recallServiceField, languageManagerField),
            this,
        )

        val root: PluginCommand? = getCommand("mountlicense")
        if (root != null) {
            val executor = MountLicenseCommand(this)
            root.setExecutor(executor)
            root.tabCompleter = executor
        }

        Metrics(this, 31450)

        logger.info("MountLicense ${description.version} enabled.")
    }

    override fun disablePlugin() {
    }

    fun configManager(): ConfigManager = configManagerField

    fun languageManager(): LanguageManager = languageManagerField

    fun profileRegistry(): ProfileRegistry = profileRegistryField

    fun pdcKeys(): PdcKeys = pdcKeysField

    fun vehicleIndex(): VehicleIndex = vehicleIndexField

    fun itemFactory(): ItemFactory = itemFactoryField

    fun ownershipService(): OwnershipService = ownershipServiceField

    fun parkingService(): ParkingService = parkingServiceField

    fun registryService(): RegistryService = registryServiceField

    fun recallService(): RecallService = recallServiceField

    fun reloadAll() {
        saveDefaultResources()
        try {
            migrateConfigAndLang()
        } catch (ex: MigrationException) {
            logger.severe("MountLicense reload aborted: migration failed. ${ex.message}")
            return
        }
        configManagerField.load()
        languageManagerField.setLocale(configManagerField.getLanguage())
        languageManagerField.load()
        profileRegistryField.load()
        if (::registryServiceField.isInitialized) {
            registryServiceField.refreshLoadedDisplayNames()
        }
    }

    private fun saveDefaultResources() {
        resourceFiles.saveIfMissing(listOf("config.yml", "vehicle-profiles.yml", "lang/zh_CN.yml", "lang/en_US.yml"))
    }

    @Throws(MigrationException::class)
    private fun migrateConfigAndLang() {
        val migrations = MigrationRunner(this)
        migrations.run(
            MigrationPlan.yaml("MountLicense config", "config.yml")
                .versionKey("config-version")
                .targetVersion(2)
                .addStep(NoOpMigrationStep(1, 2, "Add MountLicense config-version.")),
        )
        migrateLang(migrations, "zh_CN")
        migrateLang(migrations, "en_US")
    }

    @Throws(MigrationException::class)
    private fun migrateLang(migrations: MigrationRunner, locale: String) {
        migrations.run(
            MigrationPlan.yaml("MountLicense lang $locale", "lang/$locale.yml")
                .versionKey("lang-version")
                .targetVersion(2)
                .addStep(LegacyTextToMiniMessageStep(1, 2)),
        )
    }
}
