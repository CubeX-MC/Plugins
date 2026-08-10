package org.cubexmc.contract

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.config.ConfigReload
import org.cubexmc.config.LegacyTextToMiniMessageStep
import org.cubexmc.config.MigrationContext
import org.cubexmc.config.MigrationException
import org.cubexmc.config.MigrationPlan
import org.cubexmc.config.MigrationRunner
import org.cubexmc.config.MigrationStep
import org.cubexmc.config.NoOpMigrationStep
import org.cubexmc.config.ReloadChain
import org.cubexmc.config.ReloadFailurePolicy
import org.cubexmc.config.ReloadReport
import org.cubexmc.config.ResourceFiles
import org.cubexmc.contract.command.ContractCommand
import org.cubexmc.contract.config.LanguageManager
import org.cubexmc.contract.config.ContractsConfigMigrations
import org.cubexmc.contract.config.LangV2ToV3Step
import org.cubexmc.contract.economy.EconomyService
import org.cubexmc.contract.gui.ContractGui
import org.cubexmc.contract.listener.ObjectiveListener
import org.cubexmc.contract.model.BatchRepeatPolicy
import org.cubexmc.contract.service.ContractService
import org.cubexmc.contract.service.ContractTemplateService
import org.cubexmc.contract.storage.ContractStorage
import org.cubexmc.contract.storage.ContractTemplateStore
import org.cubexmc.contract.storage.BatchAcceptanceStore
import org.cubexmc.contract.storage.EventLog
import org.cubexmc.contract.storage.PendingTransactionStore
import org.cubexmc.contract.storage.ReputationStore
import org.cubexmc.core.CubexPlugin
import org.cubexmc.core.Reloadable
import org.cubexmc.scheduler.CubexScheduler
import java.io.IOException
import kotlin.math.max

class ContractPlugin : CubexPlugin() {
    private var languageManager: LanguageManager? = null
    private var economyService: EconomyService? = null
    private var contractStorage: ContractStorage? = null
    private var reputationStore: ReputationStore? = null
    private var batchAcceptanceStore: BatchAcceptanceStore? = null
    private var templateStore: ContractTemplateStore? = null
    private var templateService: ContractTemplateService? = null
    private var pendingStore: PendingTransactionStore? = null
    private var eventLog: EventLog? = null
    private var contractService: ContractService? = null
    private var contractGui: ContractGui? = null
    private var resourceFiles: ResourceFiles? = null
    private var cubexScheduler: CubexScheduler? = null

    override fun enablePlugin() {
        cubexScheduler = CubexScheduler.bindTo(this)
        resourceFiles = ResourceFiles(this)
        saveDefaultFiles()
        try {
            migrateConfigAndLang()
        } catch (ex: MigrationException) {
            log().severe("Contracts enable aborted: migration failed. ${ex.message}")
            abortEnable("Contracts migration failed. See logs for details.")
        }
        reloadConfig()

        languageManager = LanguageManager(this)
        lang().load()

        economyService = EconomyService(this)
        if (!economy().setup()) {
            abortEnable("Vault economy provider not found. Contracts will be disabled.")
        }

        // Every store is a Terminable, so bind() owns shutdown flushing; TerminableRegistry closes
        // them in reverse registration order.
        contractStorage = bind(ContractStorage(this))
        storage().load()
        // Contracts flush on a full save rather than the incremental flushIfDirty the others use.
        bind(Runnable {
            try {
                contractStorage?.save()
            } catch (ex: IOException) {
                log().warn("Failed to save contracts on disable: ${ex.message}")
            }
        })

        reputationStore = bind(ReputationStore(this))
        reputation().load()

        batchAcceptanceStore = bind(BatchAcceptanceStore(this))
        batchAcceptances().load()
        reconcileBatchAcceptances()

        templateStore = bind(ContractTemplateStore(this))
        templates().load()
        rebuildTemplateService()

        pendingStore = PendingTransactionStore(this)
        eventLog = EventLog(this)
        contractService = ContractService(this, storage(), economy(), pending(), eventLog(), batchAcceptances())
        contracts().recoverPendingTransactions()
        val overdue = contracts().activateScheduled()
        if (overdue > 0) log().info("Published $overdue overdue scheduled contracts during startup.")
        contractGui = bind(ContractGui(this))
        registerListener(gui())
        registerListener(gui().registry)
        registerListener(gui().input)
        registerListener(ObjectiveListener(this))

        registerCommand("contract", ContractCommand(this))

        scheduleCleanup()
        schedulePublication()
        scheduleFlush()
        Metrics(this, 31491)
        log().info("Contract enabled with ${storage().all().size} stored contracts.")
    }

    override fun disablePlugin() {
    }

    /**
     * Reloads config, language and on-disk data as a named [ReloadChain].
     *
     * Two ordering rules make this more than a list of calls, and both are expressed by the chain
     * rather than by hand-rolled flags:
     *
     * - the data stages are **gated** on the flush succeeding. Reloading contracts from disk after a
     *   failed flush would silently discard whatever was still only in memory;
     * - migration uses [ReloadFailurePolicy.ABORT], because reloading config or language on top of a
     *   half-migrated file is worse than leaving the running state alone.
     *
     * Returns the report so the command layer can tell the operator exactly which stage failed.
     */
    fun reloadContracts(): ReloadReport {
        var flushed = true

        val report = ReloadChain.create()
            .failurePolicy(ReloadFailurePolicy.ABORT)
            .add("gui-sessions", Reloadable { contractGui?.closeSessions() })
            .add("flush-stores", Reloadable {
                try {
                    storage().flushIfDirty()
                    batchAcceptances().flushIfDirty()
                    templates().flushIfDirty()
                } catch (ex: IOException) {
                    flushed = false
                    log().warn("Reload: could not flush contracts; keeping in-memory state and skipping the data stages. ${ex.message}")
                }
            })
            .add("default-files", Reloadable { saveDefaultFiles() })
            .add("migrations", Reloadable { migrateConfigAndLang() })
            .add("config", ConfigReload.bukkitConfig(this))
            .add("language", lang())
            .add("template-service", Reloadable { rebuildTemplateService() })
            .addIf("contracts", { flushed }, storage())
            .addIf("batch-acceptances", { flushed }, batchAcceptances())
            .addIf("templates", { flushed }, templates())
            .addIf("batch-reconcile", { flushed }, Reloadable { reconcileBatchAcceptances() })
            .run()

        for (summary in report.failureSummaries()) {
            log().severe("Contracts reload stage failed — $summary")
        }
        if (report.skipped().isNotEmpty()) {
            log().warn("Contracts reload skipped stages: ${report.skipped().joinToString(", ")}")
        }
        return report
    }

    private fun rebuildTemplateService() {
        templateService = ContractTemplateService(templates(), config.getInt("limits.max-templates-per-player", 32))
    }

    fun lang(): LanguageManager = languageManager ?: throw IllegalStateException("languageManager not initialized")

    fun economy(): EconomyService = economyService ?: throw IllegalStateException("economyService not initialized")

    fun storage(): ContractStorage = contractStorage ?: throw IllegalStateException("contractStorage not initialized")

    fun reputation(): ReputationStore = reputationStore ?: throw IllegalStateException("reputationStore not initialized")

    fun batchAcceptances(): BatchAcceptanceStore =
        batchAcceptanceStore ?: throw IllegalStateException("batchAcceptanceStore not initialized")

    fun contracts(): ContractService = contractService ?: throw IllegalStateException("contractService not initialized")

    fun templates(): ContractTemplateStore = templateStore ?: throw IllegalStateException("templateStore not initialized")

    fun templateService(): ContractTemplateService = templateService ?: throw IllegalStateException("templateService not initialized")

    fun gui(): ContractGui = contractGui ?: throw IllegalStateException("contractGui not initialized")

    fun scheduler(): CubexScheduler = cubexScheduler ?: throw IllegalStateException("scheduler not initialized")

    private fun pending(): PendingTransactionStore = pendingStore ?: throw IllegalStateException("pendingStore not initialized")

    private fun eventLog(): EventLog = eventLog ?: throw IllegalStateException("eventLog not initialized")

    private fun reconcileBatchAcceptances() {
        var recovered = 0
        for (contract in storage().all()) {
            val batchId = contract.metadata["batch-id"] ?: continue
            if (BatchRepeatPolicy.fromStored(contract.metadata["repeat-policy"]) == BatchRepeatPolicy.UNLIMITED) {
                continue
            }
            val contractorUuid = contract.contractorUuid() ?: continue
            val acceptedAt = contract.acceptedAt() ?: continue
            if (batchAcceptances().record(batchId, contractorUuid, acceptedAt, contract.id())) {
                recovered++
            }
        }
        if (recovered > 0) {
            log().info("Recovered $recovered batch acceptance history entries from stored contracts.")
        }
    }

    private fun saveDefaultFiles() {
        resourceFiles?.saveIfMissing(listOf("config.yml", "lang/zh_CN.yml", "lang/en_US.yml"))
    }

    @Throws(MigrationException::class)
    private fun migrateConfigAndLang() {
        val migrations = MigrationRunner(this)
        migrations.run(
            MigrationPlan.yaml("Contracts config", "config.yml")
                .versionKey("config-version")
                .targetVersion(6)
                .addStep(NoOpMigrationStep(1, 2, "Add Contracts config-version."))
                .addStep(deadlineHoursToDaysStep())
                .addStep(batchContractLimitStep())
                .addStep(repeatCooldownLimitStep())
                .addStep(ContractsConfigMigrations.schedulingAndTemplatesStep()),
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
                .targetVersion(3)
                .addStep(LegacyTextToMiniMessageStep(1, 2))
                .addStep(LangV2ToV3Step(this)),
        )
    }

    private fun scheduleFlush() {
        val intervalSeconds = max(5, config.getLong("storage.flush-interval-seconds", 30))
        val periodTicks = intervalSeconds * 20L
        scheduler().runGlobalTimer(Runnable {
            try {
                contracts().flushStores()
            } catch (ex: IOException) {
                log().warn("Contract flush failed: ${ex.message}")
            }
        }, periodTicks, periodTicks)
    }

    /** v3 -> v4: expose the maximum number of SERVICE contracts created in one batch. */
    private fun batchContractLimitStep(): MigrationStep = object : MigrationStep {
        override fun fromVersion(): Int = 3

        override fun toVersion(): Int = 4

        override fun description(): String = "Add the SERVICE batch creation limit."

        override fun migrate(context: MigrationContext) {
            if (!context.yaml().contains("limits.max-batch-contracts")) {
                context.yaml()["limits.max-batch-contracts"] = 64
            }
        }
    }

    /** v4 -> v5: bound the cooldown configured for repeatable SERVICE batches. */
    private fun repeatCooldownLimitStep(): MigrationStep = object : MigrationStep {
        override fun fromVersion(): Int = 4

        override fun toVersion(): Int = 5

        override fun description(): String = "Add the SERVICE batch repeat cooldown limit."

        override fun migrate(context: MigrationContext) {
            if (!context.yaml().contains("limits.max-repeat-cooldown-hours")) {
                context.yaml()["limits.max-repeat-cooldown-hours"] = 8760
            }
        }
    }

    private fun scheduleCleanup() {
        val intervalMinutes = max(1, config.getLong("expiry.cleanup-interval-minutes", 10))
        val periodTicks = intervalMinutes * 60L * 20L
        scheduler().runGlobalTimer(Runnable {
            val changed = contracts().cleanupExpired()
            if (changed > 0) {
                log().info("Processed $changed contract cleanup actions.")
            }
        }, periodTicks, periodTicks)
    }


    private fun schedulePublication() {
        val intervalSeconds = max(5, config.getLong("scheduling.scan-interval-seconds", 30))
        val periodTicks = intervalSeconds * 20L
        scheduler().runGlobalTimer(Runnable {
            val activated = contracts().activateScheduled()
            if (activated > 0) log().info("Published $activated scheduled contracts.")
        }, periodTicks, periodTicks)
    }
}
