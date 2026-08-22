package org.cubexmc.metro

import net.megavex.scoreboardlibrary.api.ScoreboardLibrary
import net.megavex.scoreboardlibrary.api.exception.NoPacketAdapterAvailableException
import net.megavex.scoreboardlibrary.api.noop.NoopScoreboardLibrary
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Minecart
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.core.CubexPlugin
import org.cubexmc.metro.api.MetroAPI
import org.cubexmc.metro.config.ConfigFacade
import org.cubexmc.metro.estimation.TravelTimeEstimator
import org.cubexmc.metro.gui.ChatInputManager
import org.cubexmc.metro.gui.GuiListener
import org.cubexmc.metro.gui.GuiManager
import org.cubexmc.metro.integration.VaultIntegration
import org.cubexmc.metro.lifecycle.CommandRegistration
import org.cubexmc.metro.lifecycle.ListenerRegistration
import org.cubexmc.metro.lifecycle.MapIntegrationLifecycle
import org.cubexmc.metro.lifecycle.ScheduledTaskLifecycle
import org.cubexmc.metro.listener.PlayerInteractListener
import org.cubexmc.metro.listener.PlayerMoveListener
import org.cubexmc.metro.listener.VehicleListener
import org.cubexmc.metro.model.EntityModelController
import org.cubexmc.metro.model.Stop
import org.cubexmc.metro.manager.LanguageManager
import org.cubexmc.metro.manager.LineManager
import org.cubexmc.metro.manager.PortalManager
import org.cubexmc.metro.manager.RailProtectionManager
import org.cubexmc.metro.manager.RouteRecorder
import org.cubexmc.metro.manager.SelectionManager
import org.cubexmc.metro.manager.StopManager
import org.cubexmc.metro.persistence.SaveCoordinator
import org.cubexmc.metro.service.LineSelectionService
import org.cubexmc.metro.service.LineServiceManager
import org.cubexmc.metro.service.LineStatusService
import org.cubexmc.metro.service.PriceService
import org.cubexmc.metro.service.TicketService
import org.cubexmc.metro.train.ScoreboardManager
import org.cubexmc.metro.train.TrainDisplayController
import org.cubexmc.metro.train.TrainMovementTask
import org.cubexmc.metro.update.DataFileUpdater
import org.cubexmc.metro.update.MetroMigrations
import org.cubexmc.metro.util.MetroConstants
import org.cubexmc.metro.util.SchedulerUtil
import org.cubexmc.metro.util.VersionUtil
import org.incendo.cloud.annotations.AnnotationParser
import java.io.File

class Metro : CubexPlugin() {

    lateinit var lineManager: LineManager
        private set

    lateinit var stopManager: StopManager
        private set

    lateinit var languageManager: LanguageManager
        private set

    var globalScoreboardLibrary: ScoreboardLibrary? = null
        private set

    lateinit var scoreboardManager: ScoreboardManager
        private set

    lateinit var selectionManager: SelectionManager
        private set

    lateinit var guiManager: GuiManager
        private set

    lateinit var chatInputManager: ChatInputManager
        private set

    lateinit var configFacade: ConfigFacade
        private set

    lateinit var playerInteractListener: PlayerInteractListener
        private set

    var vehicleListener: VehicleListener? = null
        private set

    var playerMoveListener: PlayerMoveListener? = null
        private set

    var guiListener: GuiListener? = null
        private set

    var trainDisplayController: TrainDisplayController? = null
        private set

    var commandManager: org.incendo.cloud.CommandManager<CommandSender>? = null
        private set

    var annotationParser: AnnotationParser<CommandSender>? = null
        private set

    lateinit var portalManager: PortalManager
        private set

    lateinit var routeRecorder: RouteRecorder
        private set

    var railProtectionManager: RailProtectionManager? = null
        private set

    var vaultIntegration: VaultIntegration? = null
        private set

    lateinit var lineSelectionService: LineSelectionService
        private set

    lateinit var ticketService: TicketService
        private set

    lateinit var priceService: PriceService
        private set

    lateinit var lineStatusService: LineStatusService
        private set

    lateinit var saveCoordinator: SaveCoordinator
        private set

    lateinit var travelTimeEstimator: TravelTimeEstimator
        private set

    lateinit var lineServiceManager: LineServiceManager
        private set

    var entityModelController: EntityModelController? = null
        private set

    private var mapIntegrationLifecycle: MapIntegrationLifecycle? = null
    private var scheduledTaskLifecycle: ScheduledTaskLifecycle? = null

    /**
     * 配置门面是否已就绪。生命周期组件可能在 enable 完成前被回调。
     */
    fun isConfigFacadeReady(): Boolean = ::configFacade.isInitialized

    @Throws(Exception::class)
    override fun enablePlugin() {
        bindShutdownActions()

        // 创建配置目录
        if (!dataFolder.exists()) {
            dataFolder.mkdirs()
        }

        // 初始化并迁移配置文件
        MetroMigrations.ensureConfigResources(this)
        MetroMigrations.migrateConfig(this)
        reloadConfig()
        configFacade = ConfigFacade(this)
        configFacade.reload()

        // 初始化默认配置文件
        MetroMigrations.ensureEntityDefaults(this)
        DataFileUpdater.migrateAll(this)

        // 初始化并迁移语言文件
        MetroMigrations.ensureLanguageResources(this)
        MetroMigrations.migrateBundledLanguages(this)
        languageManager = LanguageManager(this)
        saveCoordinator = SaveCoordinator(logger) { command -> SchedulerUtil.asyncRun(this, command, 0L) }

        // 初始化管理器
        lineManager = LineManager(this)
        val protectionManager = RailProtectionManager(this)
        railProtectionManager = protectionManager
        protectionManager.rebuildAll()
        stopManager = StopManager(this)
        lineSelectionService = LineSelectionService(lineManager, stopManager)
        selectionManager = SelectionManager()
        guiManager = GuiManager(this)
        chatInputManager = ChatInputManager(this)
        routeRecorder = RouteRecorder(this)
        Bukkit.getPluginManager().registerEvents(chatInputManager, this)
        // Paper 上再补一条现代聊天事件监听(Spigot 上是空操作),两条链路都能接住。
        chatInputManager.registerModernChat()

        lineServiceManager = LineServiceManager(this)

        // 初始化传送门管理器
        val portals = PortalManager(this)
        portalManager = portals

        // 初始化经济集成
        val economy = VaultIntegration(this)
        vaultIntegration = economy
        if (economy.isEnabled()) {
            logger.info("Vault economy integration enabled.")
        } else {
            logger.info("Vault economy not found or disabled.")
        }
        ticketService = TicketService({ vaultIntegration }, { config.getBoolean("economy.enabled", true) })

        priceService = PriceService()
        lineStatusService = LineStatusService(this, lineManager)

        // 初始化计分板库
        globalScoreboardLibrary =
            try {
                ScoreboardLibrary.loadScoreboardLibrary(this)
            } catch (_: NoPacketAdapterAvailableException) {
                logger.warning("当前服务端暂无可用 ScoreboardLibrary 数据包适配器，计分板显示将临时不可见。")
                NoopScoreboardLibrary()
            }

        // 初始化计分板管理器
        scoreboardManager = ScoreboardManager(this)
        MetroConstants.initialize(this)

        entityModelController = EntityModelController(this).also { it.reload() }

        val commandRegistration =
            CommandRegistration(this, lineManager, stopManager, portals).register() ?: return
        commandManager = commandRegistration.commandManager()
        annotationParser = commandRegistration.annotationParser()

        val listenerRegistration = ListenerRegistration(this, protectionManager).register()
        playerInteractListener = listenerRegistration.playerInteractListener()
        vehicleListener = listenerRegistration.vehicleListener()
        playerMoveListener = listenerRegistration.playerMoveListener()
        guiListener = listenerRegistration.guiListener()
        trainDisplayController = listenerRegistration.trainDisplayController()

        entityModelController?.let { controller ->
            Bukkit.getPluginManager().registerEvents(
                org.cubexmc.metro.listener.EntityModelListener(this),
                this,
            )
            if (config.getBoolean("entity-model.enabled", false)) {
                logger.info("Entity model mode enabled: ${controller.getDefaultEntityTypeRaw()}")
            }
        }

        // 注册bstats
        Metrics(this, BSTATS_PLUGIN_ID)

        travelTimeEstimator = TravelTimeEstimator(this)
        travelTimeEstimator.load()

        val scheduledTasks = ScheduledTaskLifecycle(this, lineManager, stopManager, portals)
        scheduledTaskLifecycle = scheduledTasks
        scheduledTasks.start()

        val mapIntegrations = MapIntegrationLifecycle(this)
        mapIntegrationLifecycle = mapIntegrations
        mapIntegrations.enable()

        MetroAPI.initialize(this)

        SchedulerUtil.ensureTickCounter(this)

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                org.cubexmc.metro.placeholder.RailwayPlaceholders(this).register()
                logger.info("Registered Railway placeholders with PlaceholderAPI")
            } catch (throwable: Throwable) {
                logger.warning("Failed to register PlaceholderAPI: ${throwable.message}")
            }
        }

        logger.info("Railway has been enabled!")
    }

    override fun disablePlugin() {
        // 关闭动作全部通过 bindShutdownActions() 注册
    }

    private fun bindShutdownActions() {
        bind {
            if (::languageManager.isInitialized) {
                Bukkit.getConsoleSender().sendMessage(languageManager.getMessage("plugin.disabled"))
            } else {
                logger.info("Metro plugin disabled.")
            }
        }
        bind { flushPersistentData() }
        bind {
            if (::routeRecorder.isInitialized) {
                routeRecorder.cancelAll()
            }
        }
        bind { scheduledTaskLifecycle?.shutdown() }
        bind { removeFallbackMinecarts() }
        bind { shutdownActiveTrains() }
        bind { clearPlayerDisplays() }
        bind { globalScoreboardLibrary?.close() }
        bind {
            if (::scoreboardManager.isInitialized) {
                scoreboardManager.shutdown()
            }
        }
        bind {
            if (::playerInteractListener.isInitialized) {
                playerInteractListener.shutdown()
            }
        }
        bind { playerMoveListener?.shutdown() }
        bind { mapIntegrationLifecycle?.disable() }
    }

    private fun clearPlayerDisplays() {
        if (!::scoreboardManager.isInitialized) {
            return
        }
        for (player in Bukkit.getOnlinePlayers()) {
            scoreboardManager.clearPlayerDisplay(player)
        }
    }

    private fun shutdownActiveTrains() {
        val activeTrainCount = TrainMovementTask.shutdownActiveTasks(this, VersionUtil.isFolia())
        if (activeTrainCount > 0) {
            logger.info("Cleaned up $activeTrainCount active Metro train(s).")
        }
    }

    private fun removeFallbackMinecarts() {
        // Paper/Bukkit 兜底清理旧残留；Folia 不做全世界实体扫描，避免跨 region 访问风险。
        if (VersionUtil.isFolia()) {
            logger.info(
                "Skipped fallback world minecart scan during Folia shutdown; " +
                    "active trains were cleaned through the train registry.",
            )
            return
        }
        val minecartKey = MetroConstants.getMinecartKey() ?: return
        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities) {
                if (entity is Minecart &&
                    entity.persistentDataContainer.has(minecartKey, PersistentDataType.BYTE)
                ) {
                    entity.eject()
                    entity.remove()
                }
            }
        }
    }

    /**
     * 重新创建默认配置文件（如果不存在）
     * 此方法用于reload命令，确保所有配置文件都能够被重新生成
     */
    fun ensureDefaultConfigs() {
        // 确保主配置文件存在
        if (!File(dataFolder, "config.yml").exists()) {
            saveDefaultConfig()
            logger.info("重新生成默认主配置文件")
        }

        // 确保其他配置文件存在
        createDefaultConfigFiles()
    }

    /**
     * 创建默认配置文件
     */
    private fun createDefaultConfigFiles() {
        // 确保这些文件存在于插件数据文件夹中
        saveDefaultConfigFiles("lines.yml")
        saveDefaultConfigFiles("stops.yml")
        saveDefaultConfigFiles("entity.yml")
    }

    /**
     * 保存默认配置文件
     *
     * @param fileName 文件名
     */
    private fun saveDefaultConfigFiles(fileName: String) {
        if (!File(dataFolder, fileName).exists()) {
            saveResource(fileName, false)
        }
    }

    /**
     * 是否启用调试日志。
     */
    fun isDebugEnabled(): Boolean = configFacade.isDebugEnabled()

    /**
     * 是否启用某个调试分类。
     *
     * @param category 调试分类键，例如 train_state_transitions
     */
    fun isDebugCategoryEnabled(category: String): Boolean = configFacade.isDebugCategoryEnabled(category)

    /**
     * 输出分类调试日志。
     */
    fun debug(category: String, message: String) {
        if (!isDebugCategoryEnabled(category)) {
            return
        }
        logger.info("[DEBUG][$category] $message")
    }

    fun refreshMapIntegrations() {
        mapIntegrationLifecycle?.refresh()
    }

    fun requestMapIntegrationRefresh() {
        mapIntegrationLifecycle?.requestRefresh()
    }

    fun isPlayerWithinStopRadius(stop: Stop?, radiusSquared: Double): Boolean {
        val base: Location = stop?.stopPointLocation ?: return false
        val world = base.world ?: return false
        for (player in Bukkit.getOnlinePlayers()) {
            if (player.world != world) continue
            val playerLocation = player.location
            if (stop.isInStop(playerLocation) || playerLocation.distanceSquared(base) <= radiusSquared) return true
        }
        return false
    }

    fun flushPersistentData() {
        if (::lineManager.isInitialized) {
            lineManager.forceSaveSync()
        }
        if (::stopManager.isInitialized) {
            stopManager.forceSaveSync()
        }
        if (::portalManager.isInitialized) portalManager.forceSaveSync()
        if (::saveCoordinator.isInitialized) {
            saveCoordinator.flushAll()
        }
    }

    // Railway service configuration. Kotlin properties retain the original Java getter names.
    fun config(): Metro = this

    val controlMode: String
        get() = getStringConfig("train.control-mode", "settings.control.default_mode", "kinematic")
    val isPhysicsLeadKinematic: Boolean
        get() = getBooleanConfig("train.physics-lead-kinematic", "physics.lead_kinematic", true)
    val isSafeSpeedMode: Boolean
        get() = getBooleanConfig("train.safe-speed-mode", "settings.safe_speed_mode", true)
    val physicsLookaheadBlocks: Int
        get() = getIntConfig("train.physics-lookahead-blocks", "physics.lookahead_blocks", 8)
    val leashOffsetY: Double
        get() = getDoubleConfig("train.leash-offset-y", "settings.control.leash.offset_y", 1.5)
    val leashMobTypeRaw: String
        get() = getStringConfig("train.leash-mob-type", "settings.control.leash.mob_type", "PIG")

    val serviceDefaultHeadwaySeconds: Int
        get() = config.getInt("service.default-headway-seconds", 120)
    val serviceMetricsLogIntervalTicks: Int
        get() = config.getInt("service.metrics-log-interval-ticks", 1200)

    val chunkLoadingRadius: Int
        get() = config.getInt("chunk-loading.radius", 3)
    val chunkLoadingUpdateIntervalTicks: Int
        get() = config.getInt("chunk-loading.update-interval-ticks", 20)
    val isChunkLoadingEnabled: Boolean
        get() = config.getBoolean("chunk-loading.enabled", true)
    val isChunkLoadingOnlyWhenMoving: Boolean
        get() = config.getBoolean("chunk-loading.only-when-moving", true)
    val forwardPreloadRadius: Int
        get() = config.getInt("chunk-loading.forward-preload-radius", 5)

    val localActivationRadius: Double
        get() = getDoubleConfig("service.local.activation-radius", "settings.local.activation_radius", 256.0)
    val localRailSearchRadius: Int
        get() = config.getInt("service.local.rail-search-radius", 5)
    val localSpawnMode: String
        get() = getStringConfig("service.local.spawn-mode", "settings.local.virtual_network.spawn_mode", "nearest")
    val localVirtualIdleTicks: Int
        get() = config.getInt("service.local.virtual-idle-ticks", 200)
    val localVirtualLookaheadStops: Int
        get() = getIntConfig("service.local.virtual-lookahead-stops", "settings.local.virtual_network.lookahead_stops", 3)
    val isLocalVirtualizationEnabled: Boolean
        get() = config.getBoolean("service.local.virtualization-enabled", false)

    val trainName: String
        get() = config.getString("train.name", "") ?: ""
    val isTrainNameVisible: Boolean
        get() = config.getBoolean("train.name-visible", true)

    val isArriveStopTitleEnabled: Boolean
        get() = getBooleanConfig("titles.arrive-stop.enabled", "titles.arrive_stop.enabled", true)
    val arriveStopTitle: String
        get() = getStringConfig("titles.arrive-stop.title", "titles.arrive_stop.title", "&b{stop_name} &fArrived")
    val arriveStopSubtitle: String
        get() = getStringConfig("titles.arrive-stop.subtitle", "titles.arrive_stop.subtitle", "")
    val arriveStopFadeIn: Int
        get() = getIntConfig("titles.arrive-stop.fade-in", "titles.arrive_stop.fade_in", 10)
    val arriveStopStay: Int
        get() = config.getInt("titles.arrive-stop.stay", config.getInt("titles.arrive_stop.stay", 40))
    val arriveStopFadeOut: Int
        get() = getIntConfig("titles.arrive-stop.fade-out", "titles.arrive_stop.fade_out", 10)

    val isDepartureTitleEnabled: Boolean
        get() = config.getBoolean("titles.departure.enabled", true)
    val departureTitle: String
        get() = config.getString("titles.departure.title", "Next Stop &e{next_stop_name}") ?: ""
    val departureSubtitle: String
        get() = config.getString("titles.departure.subtitle", "") ?: ""
    val departureFadeIn: Int
        get() = getIntConfig("titles.departure.fade-in", "titles.departure.fade_in", 5)
    val departureStay: Int
        get() = config.getInt("titles.departure.stay", 40)
    val departureFadeOut: Int
        get() = getIntConfig("titles.departure.fade-out", "titles.departure.fade_out", 5)

    val isWaitingTitleEnabled: Boolean
        get() = config.getBoolean("titles.waiting.enabled", true)
    val waitingTitle: String
        get() = config.getString("titles.waiting.title", "Train Departing Soon") ?: ""
    val waitingSubtitle: String
        get() = config.getString("titles.waiting.subtitle", "") ?: ""
    val waitingInterval: Int
        get() = config.getInt("titles.waiting.interval", 20)

    val isTerminalStopTitleEnabled: Boolean
        get() = getBooleanConfig("titles.terminal-stop.enabled", "titles.terminal_stop.enabled", true)
    val terminalStopTitle: String
        get() = getStringConfig("titles.terminal-stop.title", "titles.terminal_stop.title", "&b{stop_name}")
    val terminalStopSubtitle: String
        get() = getStringConfig(
            "titles.terminal-stop.subtitle",
            "titles.terminal_stop.subtitle",
            "&cTerminal Station - Please Exit",
        )
    val terminalStopFadeIn: Int
        get() = getIntConfig("titles.terminal-stop.fade-in", "titles.terminal_stop.fade_in", 10)
    val terminalStopStay: Int
        get() = config.getInt("titles.terminal-stop.stay", config.getInt("titles.terminal_stop.stay", 60))
    val terminalStopFadeOut: Int
        get() = getIntConfig("titles.terminal-stop.fade-out", "titles.terminal_stop.fade_out", 10)

    val waitingActionbar: String
        get() = getStringConfig("actionbar.waiting", "titles.waiting.actionbar", "")
    val departureActionbar: String
        get() = getStringConfig("actionbar.departing", "titles.departure.actionbar", "")
    val arrivalInitialDelay: Int
        get() = config.getInt("sounds.arrival.initial_delay", 20)
    val departureInitialDelay: Int
        get() = config.getInt("sounds.departure.initial_delay", 0)
    val departureInterval: Int
        get() = getIntConfig("titles.departure-interval", "titles.departure.interval", 40)
    val arrivalNotes: List<String>
        get() = config.getStringList("sounds.arrival.notes").ifEmpty {
            listOf("NOTE,6,0.8,CHIME,0", "NOTE,10,0.9,CHIME,4", "NOTE,13,1.0,CHIME,8")
        }
    val departureNotes: List<String>
        get() = config.getStringList("sounds.departure.notes").ifEmpty {
            listOf("NOTE,18,1.0,PLING,0", "NOTE,18,1.0,PLING,4")
        }
    val isArrivalSoundEnabled: Boolean
        get() = isRailwaySoundEnabled("sounds.arrival.enabled", "sounds.arrival.notes")
    val isDepartureSoundEnabled: Boolean
        get() = isRailwaySoundEnabled("sounds.departure.enabled", "sounds.departure.notes")

    val isTravelTimeEnabled: Boolean
        get() = config.getBoolean("travel-time.enabled", false)
    val defaultSectionSeconds: Double
        get() = config.getDouble("travel-time.default-section-seconds", 10.0)
    val priorStrength: Double
        get() = config.getDouble("travel-time.prior-strength", 3.0)
    val outlierSigma: Double
        get() = config.getDouble("travel-time.outlier-sigma", 4.0)
    val decayPerDay: Double
        get() = config.getDouble("travel-time.decay-per-day", 0.05)
    val unboardedSampleWeight: Double
        get() = config.getDouble("travel-time.unboarded-sample-weight", 0.5)
    val isUseUnboardedSamples: Boolean
        get() = config.getBoolean("travel-time.use-unboarded-samples", true)

    val entityTypeOverride: String
        get() = config.getString("entity-model.entity-type-override", "") ?: ""
    val serviceModeRaw: String
        get() = config.getString("service.mode", "local") ?: "local"
    val cartSpeed: Double
        get() = getDoubleConfig("cart-speed", "settings.cart_speed", 0.4)
    val trainSpacing: Double
        get() = config.getDouble("train-spacing", 3.0)
    val serviceHeartbeatIntervalTicks: Int
        get() = config.getInt("service.heartbeat-interval-ticks", 2)

    private fun isRailwaySoundEnabled(enabledPath: String, notesPath: String): Boolean {
        if (!config.contains(enabledPath)) return true
        return config.getBoolean(enabledPath, true) || !config.contains(notesPath)
    }

    private fun getStringConfig(primaryPath: String, legacyPath: String, fallback: String): String =
        if (config.contains(primaryPath)) {
            config.getString(primaryPath, fallback) ?: fallback
        } else {
            config.getString(legacyPath, fallback) ?: fallback
        }

    private fun getBooleanConfig(primaryPath: String, legacyPath: String, fallback: Boolean): Boolean =
        if (config.contains(primaryPath)) config.getBoolean(primaryPath, fallback)
        else config.getBoolean(legacyPath, fallback)

    private fun getIntConfig(primaryPath: String, legacyPath: String, fallback: Int): Int =
        if (config.contains(primaryPath)) config.getInt(primaryPath, fallback)
        else config.getInt(legacyPath, fallback)

    private fun getDoubleConfig(primaryPath: String, legacyPath: String, fallback: Double): Double =
        if (config.contains(primaryPath)) config.getDouble(primaryPath, fallback)
        else config.getDouble(legacyPath, fallback)

    private companion object {
        const val BSTATS_PLUGIN_ID = 25825
    }
}
