package org.cubexmc.regions

import org.bukkit.command.PluginCommand
import org.cubexmc.config.LegacyTextToMiniMessageStep
import org.cubexmc.config.MigrationException
import org.cubexmc.config.MigrationPlan
import org.cubexmc.config.MigrationRunner
import org.cubexmc.config.NoOpMigrationStep
import org.cubexmc.config.ResourceFiles
import org.cubexmc.core.CubexPlugin
import org.cubexmc.regions.command.RegionsCommand
import org.cubexmc.regions.config.LanguageManager
import org.cubexmc.regions.effect.ScopedEffectService
import org.cubexmc.regions.flag.RegionFlagRegistry
import org.cubexmc.regions.gui.RegionsGui
import org.cubexmc.regions.integration.BuiltInRegionSources
import org.cubexmc.regions.integration.FallbackUnionProvider
import org.cubexmc.regions.integration.LandsUnionProvider
import org.cubexmc.regions.integration.RegionSourceRegistry
import org.cubexmc.regions.integration.UnionProviderRegistry
import org.cubexmc.regions.listener.PlayerLifecycleListener
import org.cubexmc.regions.mode.CombatModeService
import org.cubexmc.regions.mode.RaceModeService
import org.cubexmc.regions.mode.RegionModeRegistry
import org.cubexmc.regions.mode.RoundModeService
import org.cubexmc.regions.service.RegionActionRegistry
import org.cubexmc.regions.service.RegionDetectionService
import org.cubexmc.regions.service.RegionFlagService
import org.cubexmc.regions.service.RegionRegistry
import org.cubexmc.regions.service.RegionSessionService
import org.cubexmc.regions.service.RegionTriggerService
import org.cubexmc.regions.service.RegionValidationService
import org.cubexmc.regions.storage.RegionStorage
import org.cubexmc.scheduler.CubexScheduler
import kotlin.math.max

class RegionsPlugin : CubexPlugin() {
    private var resourceFiles: ResourceFiles? = null
    private var languageManager: LanguageManager? = null
    private var regionStorage: RegionStorage? = null
    private var regionRegistry: RegionRegistry? = null
    private var sourceRegistry: RegionSourceRegistry? = null
    private var unionProviderRegistry: UnionProviderRegistry? = null
    private var modeRegistry: RegionModeRegistry? = null
    private var combatModeService: CombatModeService? = null
    private var raceModeService: RaceModeService? = null
    private var roundModeService: RoundModeService? = null
    private var flagRegistry: RegionFlagRegistry? = null
    private var effectService: ScopedEffectService? = null
    private var actionRegistry: RegionActionRegistry? = null
    private var sessionService: RegionSessionService? = null
    private var detectionService: RegionDetectionService? = null
    private var triggerService: RegionTriggerService? = null
    private var flagService: RegionFlagService? = null
    private var guiService: RegionsGui? = null
    private var validationService: RegionValidationService? = null
    private var cubexScheduler: CubexScheduler? = null

    override fun enablePlugin() {
        cubexScheduler = CubexScheduler.bindTo(this)
        resourceFiles = ResourceFiles(this)
        saveDefaultFiles()
        migrateConfigAndLang()
        reloadConfig()

        languageManager = LanguageManager(this)
        lang().load()

        sourceRegistry = RegionSourceRegistry()
        BuiltInRegionSources.registerAll(sources(), this)
        unionProviderRegistry = UnionProviderRegistry()
        unions().register(LandsUnionProvider(this))
        unions().register(FallbackUnionProvider())
        unions().setPreferred(config.getString("integrations.union-provider", "lands") ?: "lands")

        modeRegistry = RegionModeRegistry()
        modes().register("free_event")
        modes().register("dual_pvp")
        modes().register("union_war")
        modes().register("run_race")
        modes().register("boat_race")
        modes().register("horse_race")
        modes().register("hide_and_seek")

        flagRegistry = RegionFlagRegistry()
        flags().registerDefaults()

        effectService = ScopedEffectService(this)
        effects().registerDefaults()

        actionRegistry = RegionActionRegistry()
        actions().registerDefaults()

        triggerService = RegionTriggerService(this)
        combatModeService = CombatModeService(this)
        raceModeService = RaceModeService(this)
        roundModeService = RoundModeService(this)
        sessionService = RegionSessionService(this, effects())
        detectionService = RegionDetectionService(this)
        flagService = RegionFlagService(this)
        guiService = RegionsGui(this)
        regionStorage = RegionStorage(this)
        storage().load()
        validationService = RegionValidationService(sources(), modes(), flags(), effects(), actions())
        regionRegistry = RegionRegistry(storage(), validation())

        bind { storage().flushIfDirty() }
        bind { combatModes().cleanupAll("plugin-disable") }
        bind { roundModes().cleanupAll("plugin-disable") }
        bind { sessions().cleanupAll("plugin-disable") }

        registerListener(PlayerLifecycleListener(this))
        registerListener(gui())
        registerCommand()
        scheduleWatchdog()

        logger.info("Regions enabled with ${regions().all().size} configured regions.")
    }

    override fun disablePlugin() {
        combatModes().cleanupAll("plugin-disable")
        roundModes().cleanupAll("plugin-disable")
        sessions().cleanupAll("plugin-disable")
        storage().flushIfDirty()
    }

    fun reloadRegions() {
        if (config.getBoolean("safety.cleanup-on-reload", true)) {
            combatModes().cleanupAll("reload")
            roundModes().cleanupAll("reload")
            sessions().cleanupAll("reload")
        }
        saveDefaultFiles()
        try {
            migrateConfigAndLang()
        } catch (ex: MigrationException) {
            logger.severe("Regions reload aborted: migration failed. ${ex.message}")
            return
        }
        reloadConfig()
        lang().load()
        storage().load()
        val issues = validation().validateAll(regions().all())
        if (issues.isNotEmpty()) {
            logger.warning("Regions reloaded with ${issues.size} validation issue(s).")
        }
    }

    fun lang(): LanguageManager = languageManager ?: throw IllegalStateException("languageManager not initialized")

    fun storage(): RegionStorage = regionStorage ?: throw IllegalStateException("regionStorage not initialized")

    fun regions(): RegionRegistry = regionRegistry ?: throw IllegalStateException("regionRegistry not initialized")

    fun sources(): RegionSourceRegistry = sourceRegistry ?: throw IllegalStateException("sourceRegistry not initialized")

    fun unions(): UnionProviderRegistry = unionProviderRegistry ?: throw IllegalStateException("unionProviderRegistry not initialized")

    fun modes(): RegionModeRegistry = modeRegistry ?: throw IllegalStateException("modeRegistry not initialized")

    fun combatModes(): CombatModeService = combatModeService ?: throw IllegalStateException("combatModeService not initialized")

    fun raceModes(): RaceModeService = raceModeService ?: throw IllegalStateException("raceModeService not initialized")

    fun roundModes(): RoundModeService = roundModeService ?: throw IllegalStateException("roundModeService not initialized")

    fun flags(): RegionFlagRegistry = flagRegistry ?: throw IllegalStateException("flagRegistry not initialized")

    fun effects(): ScopedEffectService = effectService ?: throw IllegalStateException("effectService not initialized")

    fun actions(): RegionActionRegistry = actionRegistry ?: throw IllegalStateException("actionRegistry not initialized")

    fun sessions(): RegionSessionService = sessionService ?: throw IllegalStateException("sessionService not initialized")

    fun detection(): RegionDetectionService = detectionService ?: throw IllegalStateException("detectionService not initialized")

    fun triggers(): RegionTriggerService = triggerService ?: throw IllegalStateException("triggerService not initialized")

    fun flagRules(): RegionFlagService = flagService ?: throw IllegalStateException("flagService not initialized")

    fun gui(): RegionsGui = guiService ?: throw IllegalStateException("guiService not initialized")

    fun validation(): RegionValidationService = validationService ?: throw IllegalStateException("validationService not initialized")

    fun regionScheduler(): CubexScheduler = cubexScheduler ?: throw IllegalStateException("cubexScheduler not initialized")

    private fun registerCommand() {
        val command = RegionsCommand(this)
        val pluginCommand: PluginCommand? = getCommand("regions")
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command)
            pluginCommand.tabCompleter = command
        }
    }

    private fun saveDefaultFiles() {
        resourceFiles?.saveIfMissing(
            listOf(
                "config.yml",
                "regions.yml",
                "templates.yml",
                "lang/zh_CN.yml",
                "lang/en_US.yml",
            ),
        )
    }

    @Throws(MigrationException::class)
    private fun migrateConfigAndLang() {
        val migrations = MigrationRunner(this)
        migrations.run(
            MigrationPlan.yaml("Regions config", "config.yml")
                .versionKey("config-version")
                .targetVersion(1)
                .addStep(NoOpMigrationStep(0, 1, "Create Regions config-version.")),
        )
        migrations.run(
            MigrationPlan.yaml("Regions data", "regions.yml")
                .versionKey("regions-version")
                .targetVersion(1)
                .addStep(NoOpMigrationStep(0, 1, "Create Regions data version.")),
        )
        migrations.run(
            MigrationPlan.yaml("Regions templates", "templates.yml")
                .versionKey("templates-version")
                .targetVersion(1)
                .addStep(NoOpMigrationStep(0, 1, "Create Regions templates version.")),
        )
        migrateLang(migrations, "zh_CN")
        migrateLang(migrations, "en_US")
    }

    @Throws(MigrationException::class)
    private fun migrateLang(migrations: MigrationRunner, locale: String) {
        migrations.run(
            MigrationPlan.yaml("Regions lang $locale", "lang/$locale.yml")
                .versionKey("lang-version")
                .targetVersion(1)
                .addStep(LegacyTextToMiniMessageStep(0, 1)),
        )
    }

    private fun scheduleWatchdog() {
        val intervalSeconds = max(1, config.getLong("safety.watchdog-interval-seconds", 3))
        val periodTicks = intervalSeconds * 20L
        regionScheduler().runGlobalTimer(
            Runnable {
                detection().updateAllOnline()
                sessions().watchdog()
            },
            periodTicks,
            periodTicks,
        )
    }
}
