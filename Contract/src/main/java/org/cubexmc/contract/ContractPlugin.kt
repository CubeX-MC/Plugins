package org.cubexmc.contract

import org.bukkit.command.PluginCommand
import org.bukkit.scheduler.BukkitTask
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.config.LegacyTextToMiniMessageStep
import org.cubexmc.config.MigrationContext
import org.cubexmc.config.MigrationException
import org.cubexmc.config.MigrationPlan
import org.cubexmc.config.MigrationRunner
import org.cubexmc.config.MigrationStep
import org.cubexmc.config.NoOpMigrationStep
import org.cubexmc.config.ResourceFiles
import org.cubexmc.contract.command.ContractCommand
import org.cubexmc.contract.config.LanguageManager
import org.cubexmc.contract.economy.EconomyService
import org.cubexmc.contract.gui.ContractGui
import org.cubexmc.contract.service.ContractService
import org.cubexmc.contract.storage.ContractStorage
import org.cubexmc.contract.storage.EventLog
import org.cubexmc.contract.storage.PendingTransactionStore
import org.cubexmc.contract.storage.ReputationStore
import org.cubexmc.core.CubexPlugin
import java.io.IOException
import kotlin.math.max

class ContractPlugin : CubexPlugin() {
    private var languageManager: LanguageManager? = null
    private var economyService: EconomyService? = null
    private var contractStorage: ContractStorage? = null
    private var reputationStore: ReputationStore? = null
    private var pendingStore: PendingTransactionStore? = null
    private var eventLog: EventLog? = null
    private var contractService: ContractService? = null
    private var contractGui: ContractGui? = null
    private var resourceFiles: ResourceFiles? = null

    override fun enablePlugin() {
        resourceFiles = ResourceFiles(this)
        saveDefaultFiles()
        try {
            migrateConfigAndLang()
        } catch (ex: MigrationException) {
            logger.severe("Contracts enable aborted: migration failed. ${ex.message}")
            abortEnable("Contracts migration failed. See logs for details.")
        }
        reloadConfig()

        languageManager = LanguageManager(this)
        lang().load()

        economyService = EconomyService(this)
        if (!economy().setup()) {
            abortEnable("Vault economy provider not found. Contracts will be disabled.")
        }

        contractStorage = ContractStorage(this)
        storage().load()
        val saveContractStorage = Runnable {
            val activeStorage = contractStorage
            if (activeStorage != null) {
                try {
                    activeStorage.save()
                } catch (ex: IOException) {
                    logger.warning("Failed to save contracts on disable: ${ex.message}")
                }
            }
        }
        bind(saveContractStorage)

        reputationStore = ReputationStore(this)
        reputation().load()
        bind(Runnable { reputationStore?.flushIfDirty() })

        pendingStore = PendingTransactionStore(this)
        eventLog = EventLog(this)
        contractService = ContractService(this, storage(), economy(), pending(), eventLog())
        contracts().recoverPendingTransactions()
        contractGui = ContractGui(this)
        val closeContractGui = Runnable {
            contractGui?.closeSessions()
        }
        bind(closeContractGui)
        server.pluginManager.registerEvents(gui(), this)
        server.pluginManager.registerEvents(gui().registry, this)
        server.pluginManager.registerEvents(gui().input, this)

        val command = ContractCommand(this)
        val pluginCommand: PluginCommand? = getCommand("contract")
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command)
            pluginCommand.tabCompleter = command
        }

        scheduleCleanup()
        scheduleFlush()
        Metrics(this, 31491)
        logger.info("Contract enabled with ${storage().all().size} stored contracts.")
    }

    override fun disablePlugin() {
    }

    fun reloadContracts() {
        contractGui?.closeSessions()
        var canReloadData = true
        try {
            storage().flushIfDirty()
        } catch (ex: IOException) {
            canReloadData = false
            logger.warning(
                "Reload: could not flush contracts; keeping in-memory state and skipping data reload. ${ex.message}",
            )
        }
        saveDefaultFiles()
        try {
            migrateConfigAndLang()
        } catch (ex: MigrationException) {
            logger.severe("Contracts reload aborted: migration failed. ${ex.message}")
            return
        }
        reloadConfig()
        lang().load()
        if (canReloadData) {
            try {
                storage().load()
            } catch (ex: RuntimeException) {
                logger.severe("Reload: contract data unreadable (${ex.message}); keeping current in-memory contracts.")
            }
        }
    }

    fun lang(): LanguageManager = languageManager ?: throw IllegalStateException("languageManager not initialized")

    fun economy(): EconomyService = economyService ?: throw IllegalStateException("economyService not initialized")

    fun storage(): ContractStorage = contractStorage ?: throw IllegalStateException("contractStorage not initialized")

    fun reputation(): ReputationStore = reputationStore ?: throw IllegalStateException("reputationStore not initialized")

    fun contracts(): ContractService = contractService ?: throw IllegalStateException("contractService not initialized")

    fun gui(): ContractGui = contractGui ?: throw IllegalStateException("contractGui not initialized")

    private fun pending(): PendingTransactionStore = pendingStore ?: throw IllegalStateException("pendingStore not initialized")

    private fun eventLog(): EventLog = eventLog ?: throw IllegalStateException("eventLog not initialized")

    private fun saveDefaultFiles() {
        resourceFiles?.saveIfMissing(listOf("config.yml", "lang/zh_CN.yml", "lang/en_US.yml"))
    }

    @Throws(MigrationException::class)
    private fun migrateConfigAndLang() {
        val migrations = MigrationRunner(this)
        migrations.run(
            MigrationPlan.yaml("Contracts config", "config.yml")
                .versionKey("config-version")
                .targetVersion(3)
                .addStep(NoOpMigrationStep(1, 2, "Add Contracts config-version."))
                .addStep(deadlineHoursToDaysStep()),
        )
        migrateLang(migrations, "zh_CN")
        migrateLang(migrations, "en_US")
    }

    /** v2 → v3: contract deadline limits switch from hours to days. */
    private fun deadlineHoursToDaysStep(): MigrationStep = object : MigrationStep {
        override fun fromVersion(): Int = 2

        override fun toVersion(): Int = 3

        override fun description(): String = "Rename deadline limits from hours to days."

        override fun migrate(context: MigrationContext) {
            convertHoursKeyToDays(context.yaml(), "limits.min-deadline-hours", "limits.min-deadline-days")
            convertHoursKeyToDays(context.yaml(), "limits.max-deadline-hours", "limits.max-deadline-days")
        }
    }

    private fun convertHoursKeyToDays(yaml: YamlConfiguration, oldKey: String, newKey: String) {
        if (!yaml.isSet(oldKey)) {
            return
        }
        if (!yaml.isSet(newKey)) {
            yaml.set(newKey, max(1, Math.round(yaml.getInt(oldKey) / 24.0).toInt()))
        }
        yaml.set(oldKey, null)
    }

    @Throws(MigrationException::class)
    private fun migrateLang(migrations: MigrationRunner, locale: String) {
        migrations.run(
            MigrationPlan.yaml("Contracts lang $locale", "lang/$locale.yml")
                .versionKey("lang-version")
                .targetVersion(2)
                .addStep(LegacyTextToMiniMessageStep(1, 2)),
        )
    }

    private fun scheduleFlush() {
        val intervalSeconds = max(5, config.getLong("storage.flush-interval-seconds", 30))
        val periodTicks = intervalSeconds * 20L
        val task = server.scheduler.runTaskTimer(
            this,
            Runnable {
                try {
                    storage().flushIfDirty()
                } catch (ex: IOException) {
                    logger.warning("Async flush failed: ${ex.message}")
                }
                reputationStore?.flushIfDirty()
            },
            periodTicks,
            periodTicks,
        )
        bindTask(task) { handle -> (handle as BukkitTask).cancel() }
    }

    private fun scheduleCleanup() {
        val intervalMinutes = max(1, config.getLong("expiry.cleanup-interval-minutes", 10))
        val periodTicks = intervalMinutes * 60L * 20L
        val task = server.scheduler.runTaskTimer(
            this,
            Runnable {
                val changed = contracts().cleanupExpired()
                if (changed > 0) {
                    logger.info("Processed $changed contract cleanup actions.")
                }
            },
            periodTicks,
            periodTicks,
        )
        bindTask(task) { handle -> (handle as BukkitTask).cancel() }
    }
}
