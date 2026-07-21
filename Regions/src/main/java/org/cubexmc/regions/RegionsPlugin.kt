package org.cubexmc.regions

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.cubexmc.config.MigrationException
import org.cubexmc.config.ResourceFiles
import org.cubexmc.core.CubexPlugin
import org.cubexmc.regions.command.RegionsCommand
import org.cubexmc.regions.capability.BuiltInRegionCapabilities
import org.cubexmc.regions.capability.CapabilityCatalog
import org.cubexmc.regions.capability.CapabilityKind
import org.cubexmc.regions.config.LanguageManager
import org.cubexmc.regions.config.RegionBaseline
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
import org.cubexmc.regions.service.RegionAuditService
import org.cubexmc.regions.service.RegionAuthorityService
import org.cubexmc.regions.service.RegionConditionRegistry
import org.cubexmc.regions.service.RegionDetectionService
import org.cubexmc.regions.service.RegionFlagService
import org.cubexmc.regions.service.RegionLifecycleService
import org.cubexmc.regions.service.RegionOverlapResolver
import org.cubexmc.regions.service.RegionPublishingService
import org.cubexmc.regions.service.RegionRegistry
import org.cubexmc.regions.service.RegionSessionService
import org.cubexmc.regions.service.RegionTemplateService
import org.cubexmc.regions.service.RegionTriggerService
import org.cubexmc.regions.service.RegionTrialService
import org.cubexmc.regions.service.RegionValidationService
import org.cubexmc.regions.storage.RegionStorage
import org.cubexmc.scheduler.CubexScheduler
import java.io.File
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
    private var conditionRegistry: RegionConditionRegistry? = null
    private var authorityService: RegionAuthorityService? = null
    private var auditService: RegionAuditService? = null
    private var lifecycleService: RegionLifecycleService? = null
    private var publishingService: RegionPublishingService? = null
    private var overlapResolver: RegionOverlapResolver? = null
    private var capabilityCatalog: CapabilityCatalog? = null
    private var sessionService: RegionSessionService? = null
    private var detectionService: RegionDetectionService? = null
    private var triggerService: RegionTriggerService? = null
    private var flagService: RegionFlagService? = null
    private var guiService: RegionsGui? = null
    private var validationService: RegionValidationService? = null
    private var templateService: RegionTemplateService? = null
    private var trialService: RegionTrialService? = null
    private var cubexScheduler: CubexScheduler? = null

    override fun enablePlugin() {
        cubexScheduler = CubexScheduler.bindTo(this)
        resourceFiles = ResourceFiles(this)
        saveDefaultFiles()
        verifyBaselineFiles()
        reloadConfig()

        languageManager = LanguageManager(this)
        lang().load()

        sourceRegistry = RegionSourceRegistry()
        BuiltInRegionSources.registerAll(sources(), this)
        if (
            config.getBoolean("integrations.lands.required-for-startup", false) &&
            sources().find("lands")?.isAvailable() != true
        ) {
            throw IllegalStateException("Lands is required by config but is not available.")
        }
        configureAuthority()
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

        conditionRegistry = RegionConditionRegistry()
        conditions().registerDefaults()

        capabilityCatalog = CapabilityCatalog()
        BuiltInRegionCapabilities.registerAll(capabilities())
        verifyCapabilityCatalog()

        triggerService = RegionTriggerService(this)
        combatModeService = CombatModeService(this)
        raceModeService = RaceModeService(this)
        roundModeService = RoundModeService(this)
        sessionService = RegionSessionService(this, effects())
        detectionService = RegionDetectionService(this)
        flagService = RegionFlagService(this)
        templateService = RegionTemplateService(File(dataFolder, "templates.yml"))
        templates().load()
        guiService = RegionsGui(this)
        regionStorage = RegionStorage(this)
        storage().load()
        overlapResolver = RegionOverlapResolver()
        validationService = RegionValidationService(sources(), modes(), flags(), effects(), actions(), conditions(), capabilities(), overlaps())
        regionRegistry = RegionRegistry(storage(), validation())
        auditService = RegionAuditService(this)
        audit().load()
        lifecycleService = RegionLifecycleService(this)
        publishingService = RegionPublishingService(this)
        trialService = RegionTrialService(this)
        lifecycle().reconcile()

        bind { storage().flushIfDirty() }
        bind { audit().save() }

        registerListener(PlayerLifecycleListener(this))
        registerListener(gui())
        registerCommand()
        scheduleWatchdog()
        scheduleEffectRefresh()
        restoreOnlinePlayersAfterEnable()

        logger.info("Regions enabled with ${regions().all().size} configured regions.")
    }

    override fun disablePlugin() {
        combatModes().cleanupAll("plugin-disable", shuttingDown = true)
        roundModes().cleanupAll("plugin-disable", shuttingDown = true)
        raceModes().cleanupAll("plugin-disable", shuttingDown = true)
        trials().cleanupAll("plugin-disable", shuttingDown = true)
        sessions().cleanupAll("plugin-disable", shuttingDown = true)
        storage().flushIfDirty()
    }

    fun reloadRegions() {
        if (config.getBoolean("safety.cleanup-on-reload", true)) {
            trials().cleanupAll("reload")
            combatModes().cleanupAll("reload")
            roundModes().cleanupAll("reload")
            raceModes().cleanupAll("reload")
            sessions().cleanupAll("reload")
        }
        saveDefaultFiles()
        try {
            verifyBaselineFiles()
        } catch (ex: MigrationException) {
            logger.severe("Regions reload aborted: unsupported data baseline. ${ex.message}")
            return
        }
        reloadConfig()
        configureAuthority()
        lang().load()
        templates().load()
        storage().load()
        lifecycle().reconcile()
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

    fun conditions(): RegionConditionRegistry = conditionRegistry ?: throw IllegalStateException("conditionRegistry not initialized")

    fun authority(): RegionAuthorityService = authorityService ?: throw IllegalStateException("authorityService not initialized")

    fun audit(): RegionAuditService = auditService ?: throw IllegalStateException("auditService not initialized")

    fun lifecycle(): RegionLifecycleService = lifecycleService ?: throw IllegalStateException("lifecycleService not initialized")

    fun publishing(): RegionPublishingService = publishingService ?: throw IllegalStateException("publishingService not initialized")

    fun overlaps(): RegionOverlapResolver = overlapResolver ?: throw IllegalStateException("overlapResolver not initialized")

    fun capabilities(): CapabilityCatalog = capabilityCatalog ?: throw IllegalStateException("capabilityCatalog not initialized")

    fun sessions(): RegionSessionService = sessionService ?: throw IllegalStateException("sessionService not initialized")

    fun detection(): RegionDetectionService = detectionService ?: throw IllegalStateException("detectionService not initialized")

    fun triggers(): RegionTriggerService = triggerService ?: throw IllegalStateException("triggerService not initialized")

    fun flagRules(): RegionFlagService = flagService ?: throw IllegalStateException("flagService not initialized")

    fun gui(): RegionsGui = guiService ?: throw IllegalStateException("guiService not initialized")

    fun validation(): RegionValidationService = validationService ?: throw IllegalStateException("validationService not initialized")

    fun templates(): RegionTemplateService = templateService ?: throw IllegalStateException("templateService not initialized")

    fun trials(): RegionTrialService = trialService ?: throw IllegalStateException("trialService not initialized")

    fun regionScheduler(): CubexScheduler = cubexScheduler ?: throw IllegalStateException("cubexScheduler not initialized")

    private fun registerCommand() {
        val command = RegionsCommand(this)
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(
                "regions",
                "Regions root command.",
                listOf("region", "venue"),
                command,
            )
        }
    }

    private fun configureAuthority() {
        authorityService = RegionAuthorityService(
            sources(),
            config.getString("governance.ruler-permission", RegionAuthorityService.RULER_PERMISSION)
                ?: RegionAuthorityService.RULER_PERMISSION,
            config.getString("governance.superadmin-permission", RegionAuthorityService.SUPERADMIN_PERMISSION)
                ?: RegionAuthorityService.SUPERADMIN_PERMISSION,
        )
    }

    private fun verifyCapabilityCatalog() {
        verifyCapabilityKind(CapabilityKind.SOURCE, sources().all().map { it.type }.toSet())
        verifyCapabilityKind(CapabilityKind.MODE, modes().all())
        verifyCapabilityKind(CapabilityKind.FLAG, flags().all())
        verifyCapabilityKind(CapabilityKind.EFFECT, effects().allTypes())
        verifyCapabilityKind(CapabilityKind.ACTION, actions().all())
        verifyCapabilityKind(CapabilityKind.CONDITION, conditions().all())
    }

    private fun verifyCapabilityKind(kind: CapabilityKind, runtimeIds: Set<String>) {
        val descriptorIds = capabilities().stableIds(kind)
        check(runtimeIds == descriptorIds) {
            "Capability catalog mismatch for $kind: runtime=$runtimeIds descriptors=$descriptorIds"
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
    private fun verifyBaselineFiles() {
        val errors = RegionBaseline.validate(dataFolder)
        if (errors.isNotEmpty()) {
            throw MigrationException(
                errors.joinToString("; ") +
                    ". Pre-release development files are intentionally not migrated; regenerate or update them.",
            )
        }
    }

    private fun scheduleWatchdog() {
        val intervalSeconds = max(1, config.getLong("safety.watchdog-interval-seconds", 3))
        val periodTicks = intervalSeconds * 20L
        regionScheduler().runGlobalTimer(
            Runnable {
                lifecycle().reconcile()
                detection().updateAllOnline()
                sessions().watchdog()
            },
            periodTicks,
            periodTicks,
        )
    }

    private fun scheduleEffectRefresh() {
        val periodTicks = max(1L, config.getLong("effects.refresh-interval-ticks", 60L))
        regionScheduler().runGlobalTimer(
            Runnable { effects().refreshAll() },
            periodTicks,
            periodTicks,
        )
    }

    private fun restoreOnlinePlayersAfterEnable() {
        for (player in server.onlinePlayers.toList()) {
            regionScheduler().runAtEntity(player, Runnable {
                effects().restoreIfPending(player, "enable-recovery")
                combatModes().restoreIfPending(player, "enable-recovery")
                roundModes().restoreIfPending(player, "enable-recovery")
                detection().updatePlayer(player)
            })
        }
    }

}
