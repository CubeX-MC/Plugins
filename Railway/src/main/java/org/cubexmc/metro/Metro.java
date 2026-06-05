package org.cubexmc.metro;

import java.io.File;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.Location;

import org.incendo.cloud.annotations.AnnotationParser;

import org.cubexmc.core.CubexPlugin;
import org.cubexmc.metro.config.ConfigFacade;
import org.cubexmc.metro.gui.ChatInputManager;
import org.cubexmc.metro.gui.GuiListener;
import org.cubexmc.metro.gui.GuiManager;
import org.cubexmc.metro.lifecycle.CommandRegistration;
import org.cubexmc.metro.lifecycle.ListenerRegistration;
import org.cubexmc.metro.lifecycle.MapIntegrationLifecycle;
import org.cubexmc.metro.lifecycle.ScheduledTaskLifecycle;
import net.megavex.scoreboardlibrary.api.ScoreboardLibrary;
import net.megavex.scoreboardlibrary.api.exception.NoPacketAdapterAvailableException;
import net.megavex.scoreboardlibrary.api.noop.NoopScoreboardLibrary;
import org.cubexmc.metro.manager.LanguageManager;
import org.cubexmc.metro.listener.PlayerInteractListener;
import org.cubexmc.metro.listener.PlayerMoveListener;
import org.cubexmc.metro.listener.VehicleListener;
import org.cubexmc.metro.manager.LineManager;
import org.cubexmc.metro.manager.RailProtectionManager;
import org.cubexmc.metro.manager.SelectionManager;
import org.cubexmc.metro.manager.StopManager;
import org.cubexmc.metro.persistence.SaveCoordinator;
import org.cubexmc.metro.train.ScoreboardManager;
import org.cubexmc.metro.train.TrainDisplayController;
import org.cubexmc.metro.train.TrainMovementTask;
import org.cubexmc.metro.update.DataFileUpdater;
import org.cubexmc.metro.update.MetroMigrations;
import org.cubexmc.metro.estimation.TravelTimeEstimator;
import org.cubexmc.metro.util.MetroConstants;
import org.cubexmc.metro.util.SchedulerUtil;
import org.cubexmc.metro.util.VersionUtil;
import org.cubexmc.metro.model.EntityModelController;
import org.cubexmc.metro.model.Stop;
import org.cubexmc.metro.service.LineServiceManager;

public final class Metro extends CubexPlugin {

    private LineManager lineManager;
    private StopManager stopManager;
    private LanguageManager languageManager;
    private ScoreboardLibrary globalScoreboardLibrary;
    private org.cubexmc.metro.train.ScoreboardManager scoreboardManager;
    private SelectionManager selectionManager;
    private GuiManager guiManager;
    private ChatInputManager chatInputManager;
    private ConfigFacade configFacade;
    private PlayerInteractListener playerInteractListener;
    private VehicleListener vehicleListener;
    private PlayerMoveListener playerMoveListener;
    private GuiListener guiListener;
    private TrainDisplayController trainDisplayController;
    private org.incendo.cloud.CommandManager<CommandSender> commandManager;
    private AnnotationParser<CommandSender> annotationParser;
    private org.cubexmc.metro.manager.PortalManager portalManager;
    private org.cubexmc.metro.manager.RouteRecorder routeRecorder;
    private RailProtectionManager railProtectionManager;
    private org.cubexmc.metro.integration.VaultIntegration vaultIntegration;
    private org.cubexmc.metro.service.LineSelectionService lineSelectionService;
    private org.cubexmc.metro.service.TicketService ticketService;
    private org.cubexmc.metro.service.PriceService priceService;
    private org.cubexmc.metro.service.LineStatusService lineStatusService;
    private SaveCoordinator saveCoordinator;
    private TravelTimeEstimator travelTimeEstimator;
    private MapIntegrationLifecycle mapIntegrationLifecycle;
    private ScheduledTaskLifecycle scheduledTaskLifecycle;
    private LineServiceManager lineServiceManager;
    private EntityModelController entityModelController;

    @Override
    protected void enablePlugin() throws Exception {
        bindShutdownActions();

        // 创建配置目录
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        // 初始化并迁移配置文件
        MetroMigrations.ensureConfigResources(this);
        MetroMigrations.migrateConfig(this);
        reloadConfig();
        this.configFacade = new ConfigFacade(this);
        this.configFacade.reload();

        // 初始化默认配置文件
        MetroMigrations.ensureEntityDefaults(this);
        DataFileUpdater.migrateAll(this);

        // 初始化语言管理器（内部会自动更新语言文件）
        MetroMigrations.ensureLanguageResources(this);
        MetroMigrations.migrateBundledLanguages(this);
        this.languageManager = new LanguageManager(this);
        this.saveCoordinator = new SaveCoordinator(getLogger(),
                command -> org.cubexmc.metro.util.SchedulerUtil.asyncRun(this, command, 0L));

        // 初始化管理器
        this.lineManager = new LineManager(this);
        this.railProtectionManager = new RailProtectionManager(this);
        this.railProtectionManager.rebuildAll();
        this.stopManager = new StopManager(this);
        this.lineSelectionService = new org.cubexmc.metro.service.LineSelectionService(lineManager, stopManager);
        this.selectionManager = new SelectionManager();
        this.guiManager = new GuiManager(this);
        this.chatInputManager = new ChatInputManager(this);
        this.routeRecorder = new org.cubexmc.metro.manager.RouteRecorder(this);
        Bukkit.getPluginManager().registerEvents(this.chatInputManager, this);

        this.lineServiceManager = new org.cubexmc.metro.service.LineServiceManager(this);

        // 初始化传送门管理器
        this.portalManager = new org.cubexmc.metro.manager.PortalManager(this);

        // 初始化经济集成
        this.vaultIntegration = new org.cubexmc.metro.integration.VaultIntegration(this);
        if (this.vaultIntegration.isEnabled()) {
            getLogger().info("Vault economy integration enabled.");
        } else {
            getLogger().info("Vault economy not found or disabled.");
        }
        this.ticketService = new org.cubexmc.metro.service.TicketService(this::getVaultIntegration,
                () -> getConfig().getBoolean("economy.enabled", true));

        this.priceService = new org.cubexmc.metro.service.PriceService();
        this.lineStatusService = new org.cubexmc.metro.service.LineStatusService(this, lineManager);

        // 初始化计分板库
        try {
            this.globalScoreboardLibrary = ScoreboardLibrary.loadScoreboardLibrary(this);
        } catch (NoPacketAdapterAvailableException e) {
            this.globalScoreboardLibrary = new NoopScoreboardLibrary();
            getLogger().warning("当前服务端暂无可用 ScoreboardLibrary 数据包适配器，计分板显示将临时不可见。");
        }

        // 初始化计分板管理器
        scoreboardManager = new ScoreboardManager(this);
        MetroConstants.initialize(this);

        this.entityModelController = new org.cubexmc.metro.model.EntityModelController(this);
        this.entityModelController.reload();

        CommandRegistration.Result commandRegistration =
                new CommandRegistration(this, lineManager, stopManager, portalManager).register();
        if (commandRegistration == null) {
            return;
        }
        this.commandManager = commandRegistration.commandManager();
        this.annotationParser = commandRegistration.annotationParser();

        ListenerRegistration.Result listenerRegistration =
                new ListenerRegistration(this, railProtectionManager).register();
        this.playerInteractListener = listenerRegistration.playerInteractListener();
        this.vehicleListener = listenerRegistration.vehicleListener();
        this.playerMoveListener = listenerRegistration.playerMoveListener();
        this.guiListener = listenerRegistration.guiListener();
        this.trainDisplayController = listenerRegistration.trainDisplayController();

        if (entityModelController != null) {
            Bukkit.getPluginManager().registerEvents(
                    new org.cubexmc.metro.listener.EntityModelListener(this), this);
            if (getConfig().getBoolean("entity-model.enabled", false)) {
                getLogger().info("Entity model mode enabled: " + entityModelController.getDefaultEntityTypeRaw());
            }
        }

        // 注册bstats
        int pluginId = 25825; // <-- Replace with the id of your plugin!
        new Metrics(this, pluginId);

        this.travelTimeEstimator = new TravelTimeEstimator(this);
        this.travelTimeEstimator.load();

        this.scheduledTaskLifecycle = new ScheduledTaskLifecycle(this, lineManager, stopManager, portalManager);
        this.scheduledTaskLifecycle.start();

        this.mapIntegrationLifecycle = new MapIntegrationLifecycle(this);
        this.mapIntegrationLifecycle.enable();

        org.cubexmc.metro.api.MetroAPI.initialize(this);

        SchedulerUtil.ensureTickCounter(this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new org.cubexmc.metro.placeholder.RailwayPlaceholders(this).register();
                getLogger().info("Registered Railway placeholders with PlaceholderAPI");
            } catch (Throwable t) {
                getLogger().warning("Failed to register PlaceholderAPI: " + t.getMessage());
            }
        }

        getLogger().info("Railway has been enabled!");
    }

    @Override
    protected void disablePlugin() {
    }

    private void bindShutdownActions() {
        Runnable logDisabled = () -> {
            if (languageManager != null) {
                Bukkit.getConsoleSender().sendMessage(languageManager.getMessage("plugin.disabled"));
            } else {
                getLogger().info("Metro plugin disabled.");
            }
        };
        Runnable flushData = this::flushPersistentData;
        Runnable cancelRouteRecorder = () -> {
            if (routeRecorder != null) {
                routeRecorder.cancelAll();
            }
        };
        Runnable shutdownScheduledTasks = () -> {
            if (scheduledTaskLifecycle != null) {
                scheduledTaskLifecycle.shutdown();
            }
        };
        Runnable removeFallbackMinecarts = this::removeFallbackMinecarts;
        Runnable shutdownActiveTrains = this::shutdownActiveTrains;
        Runnable clearPlayerDisplays = this::clearPlayerDisplays;
        Runnable closeScoreboardLibrary = () -> {
            if (globalScoreboardLibrary != null) {
                globalScoreboardLibrary.close();
            }
        };
        Runnable shutdownScoreboardManager = () -> {
            if (scoreboardManager != null) {
                scoreboardManager.shutdown();
            }
        };
        Runnable shutdownPlayerInteractListener = () -> {
            if (playerInteractListener != null) {
                playerInteractListener.shutdown();
            }
        };
        Runnable shutdownPlayerMoveListener = () -> {
            if (playerMoveListener != null) {
                playerMoveListener.shutdown();
            }
        };
        Runnable disableMapIntegrations = () -> {
            if (this.mapIntegrationLifecycle != null) {
                this.mapIntegrationLifecycle.disable();
            }
        };

        bind(logDisabled);
        bind(flushData);
        bind(cancelRouteRecorder);
        bind(shutdownScheduledTasks);
        bind(removeFallbackMinecarts);
        bind(shutdownActiveTrains);
        bind(clearPlayerDisplays);
        bind(closeScoreboardLibrary);
        bind(shutdownScoreboardManager);
        bind(shutdownPlayerInteractListener);
        bind(shutdownPlayerMoveListener);
        bind(disableMapIntegrations);
    }

    private void clearPlayerDisplays() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (scoreboardManager != null) {
                scoreboardManager.clearPlayerDisplay(player);
            }
        }
    }

    private void shutdownActiveTrains() {
        int activeTrainCount = TrainMovementTask.shutdownActiveTasks(this, VersionUtil.isFolia());
        if (activeTrainCount > 0) {
            getLogger().info("Cleaned up " + activeTrainCount + " active Metro train(s).");
        }
    }

    private void removeFallbackMinecarts() {
        // Paper/Bukkit 兜底清理旧残留；Folia 不做全世界实体扫描，避免跨 region 访问风险。
        if (!VersionUtil.isFolia()) {
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    if (entity instanceof Minecart minecart
                            && minecart.getPersistentDataContainer().has(
                                    org.cubexmc.metro.util.MetroConstants.getMinecartKey(),
                                    org.bukkit.persistence.PersistentDataType.BYTE)) {
                        minecart.eject();
                        minecart.remove();
                    }
                }
            }
        } else {
            getLogger().info("Skipped fallback world minecart scan during Folia shutdown; active trains were cleaned through the train registry.");
        }
    }

    /**
     * 重新创建默认配置文件（如果不存在）
     * 此方法用于reload命令，确保所有配置文件都能够被重新生成
     */
    public void ensureDefaultConfigs() {
        // 确保主配置文件存在
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveDefaultConfig();
            getLogger().info("重新生成默认主配置文件");
        }

        // 确保其他配置文件存在
        createDefaultConfigFiles();
    }

    /**
     * 创建默认配置文件
     */
    private void createDefaultConfigFiles() {
        // 确保这些文件存在于插件数据文件夹中
        saveDefaultConfigFiles("lines.yml");
        saveDefaultConfigFiles("stops.yml");
        saveDefaultConfigFiles("entity.yml");
    }

    /**
     * 保存默认配置文件
     * 
     * @param fileName 文件名
     */
    private void saveDefaultConfigFiles(String fileName) {
        File file = new File(getDataFolder(), fileName);
        if (!file.exists()) {
            saveResource(fileName, false);
        }
    }

    /**
     * 获取线路管理器
     * 
     * @return 线路管理器
     */
    public LineManager getLineManager() {
        return lineManager;
    }

    /**
     * 获取停靠区管理器
     * 
     * @return 停靠区管理器
     */
    public StopManager getStopManager() {
        return stopManager;
    }

    /**
     * 获取语言管理器
     * 
     * @return 语言管理器
     */
    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public ScoreboardLibrary getGlobalScoreboardLibrary() {
        return globalScoreboardLibrary;
    }

    public org.cubexmc.metro.train.ScoreboardManager getScoreboardManager() {
        return scoreboardManager;
    }

    /**
     * 获取选区管理器
     * 
     * @return 选区管理器
     */
    public SelectionManager getSelectionManager() {
        return selectionManager;
    }

    /**
     * 获取 GUI 管理器
     * 
     * @return GUI 管理器
     */
    public GuiManager getGuiManager() {
        return guiManager;
    }

    public ChatInputManager getChatInputManager() {
        return chatInputManager;
    }

    public ConfigFacade getConfigFacade() {
        return configFacade;
    }

    /**
     * 是否启用调试日志。
     */
    public boolean isDebugEnabled() {
        return configFacade.isDebugEnabled();
    }

    /**
     * 是否启用某个调试分类。
     *
     * @param category 调试分类键，例如 train_state_transitions
     */
    public boolean isDebugCategoryEnabled(String category) {
        return configFacade.isDebugCategoryEnabled(category);
    }

    /**
     * 输出分类调试日志。
     */
    public void debug(String category, String message) {
        if (!isDebugCategoryEnabled(category)) {
            return;
        }
        getLogger().info("[DEBUG][" + category + "] " + message);
    }

    public void refreshMapIntegrations() {
        if (this.mapIntegrationLifecycle != null) {
            this.mapIntegrationLifecycle.refresh();
        }
    }

    public void requestMapIntegrationRefresh() {
        if (this.mapIntegrationLifecycle != null) {
            this.mapIntegrationLifecycle.requestRefresh();
        }
    }

    public boolean isPlayerWithinStopRadius(Stop stop, double radiusSquared) {
        if (stop == null || stop.getStopPointLocation() == null) {
            return false;
        }
        Location base = stop.getStopPointLocation();
        if (base.getWorld() == null) {
            return false;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null || player.getWorld() == null || !player.getWorld().equals(base.getWorld())) {
                continue;
            }
            Location playerLocation = player.getLocation();
            if (playerLocation == null) {
                continue;
            }
            if (stop.isInStop(playerLocation) || playerLocation.distanceSquared(base) <= radiusSquared) {
                return true;
            }
        }
        return false;
    }

    public org.cubexmc.metro.manager.PortalManager getPortalManager() {
        return portalManager;
    }

    public org.cubexmc.metro.manager.RouteRecorder getRouteRecorder() {
        return routeRecorder;
    }

    public RailProtectionManager getRailProtectionManager() {
        return railProtectionManager;
    }

    public org.cubexmc.metro.integration.VaultIntegration getVaultIntegration() {
        return vaultIntegration;
    }

    public org.cubexmc.metro.service.LineSelectionService getLineSelectionService() {
        return lineSelectionService;
    }

    public org.cubexmc.metro.service.TicketService getTicketService() {
        return ticketService;
    }

    public org.cubexmc.metro.service.PriceService getPriceService() {
        return priceService;
    }

    public org.cubexmc.metro.service.LineStatusService getLineStatusService() {
        return lineStatusService;
    }

    public SaveCoordinator getSaveCoordinator() {
        return saveCoordinator;
    }

    public TravelTimeEstimator getTravelTimeEstimator() {
        return travelTimeEstimator;
    }

    public void flushPersistentData() {
        if (lineManager != null) {
            lineManager.forceSaveSync();
        }
        if (stopManager != null) {
            stopManager.forceSaveSync();
        }
        if (portalManager != null) {
            portalManager.forceSaveSync();
        }
        if (saveCoordinator != null) {
            saveCoordinator.flushAll();
        }
    }

    public PlayerInteractListener getPlayerInteractListener() {
        return playerInteractListener;
    }

    // ===== Railway service config methods =====

    public Metro config() { return this; }

    public String getControlMode() { return getStringConfig("train.control-mode", "settings.control.default_mode", "kinematic"); }
    public boolean isPhysicsLeadKinematic() { return getBooleanConfig("train.physics-lead-kinematic", "physics.lead_kinematic", true); }
    public boolean isSafeSpeedMode() { return getBooleanConfig("train.safe-speed-mode", "settings.safe_speed_mode", true); }
    public int getPhysicsLookaheadBlocks() { return getIntConfig("train.physics-lookahead-blocks", "physics.lookahead_blocks", 8); }
    public double getLeashOffsetY() { return getDoubleConfig("train.leash-offset-y", "settings.control.leash.offset_y", 1.5); }
    public String getLeashMobTypeRaw() { return getStringConfig("train.leash-mob-type", "settings.control.leash.mob_type", "PIG"); }

    public int getServiceDefaultHeadwaySeconds() { return getConfig().getInt("service.default-headway-seconds", 120); }
    public int getServiceMetricsLogIntervalTicks() { return getConfig().getInt("service.metrics-log-interval-ticks", 1200); }

    public int getChunkLoadingRadius() { return getConfig().getInt("chunk-loading.radius", 3); }
    public int getChunkLoadingUpdateIntervalTicks() { return getConfig().getInt("chunk-loading.update-interval-ticks", 20); }
    public boolean isChunkLoadingEnabled() { return getConfig().getBoolean("chunk-loading.enabled", true); }
    public boolean isChunkLoadingOnlyWhenMoving() { return getConfig().getBoolean("chunk-loading.only-when-moving", true); }
    public int getForwardPreloadRadius() { return getConfig().getInt("chunk-loading.forward-preload-radius", 5); }

    public double getLocalActivationRadius() { return getDoubleConfig("service.local.activation-radius", "settings.local.activation_radius", 256.0); }
    public int getLocalRailSearchRadius() { return getConfig().getInt("service.local.rail-search-radius", 5); }
    public String getLocalSpawnMode() { return getStringConfig("service.local.spawn-mode", "settings.local.virtual_network.spawn_mode", "nearest"); }
    public int getLocalVirtualIdleTicks() { return getConfig().getInt("service.local.virtual-idle-ticks", 200); }
    public int getLocalVirtualLookaheadStops() { return getIntConfig("service.local.virtual-lookahead-stops", "settings.local.virtual_network.lookahead_stops", 3); }
    public boolean isLocalVirtualizationEnabled() { return getConfig().getBoolean("service.local.virtualization-enabled", false); }

    public String getTrainName() { return getConfig().getString("train.name", ""); }
    public boolean isTrainNameVisible() { return getConfig().getBoolean("train.name-visible", true); }

    public boolean isArriveStopTitleEnabled() { return getBooleanConfig("titles.arrive-stop.enabled", "titles.arrive_stop.enabled", true); }
    public String getArriveStopTitle() { return getStringConfig("titles.arrive-stop.title", "titles.arrive_stop.title", "&b{stop_name} &fArrived"); }
    public String getArriveStopSubtitle() { return getStringConfig("titles.arrive-stop.subtitle", "titles.arrive_stop.subtitle", ""); }
    public int getArriveStopFadeIn() { return getIntConfig("titles.arrive-stop.fade-in", "titles.arrive_stop.fade_in", 10); }
    public int getArriveStopStay() { return getConfig().getInt("titles.arrive-stop.stay", getConfig().getInt("titles.arrive_stop.stay", 40)); }
    public int getArriveStopFadeOut() { return getIntConfig("titles.arrive-stop.fade-out", "titles.arrive_stop.fade_out", 10); }

    public boolean isDepartureTitleEnabled() { return getConfig().getBoolean("titles.departure.enabled", true); }
    public String getDepartureTitle() { return getConfig().getString("titles.departure.title", "Next Stop &e{next_stop_name}"); }
    public String getDepartureSubtitle() { return getConfig().getString("titles.departure.subtitle", ""); }
    public int getDepartureFadeIn() { return getIntConfig("titles.departure.fade-in", "titles.departure.fade_in", 5); }
    public int getDepartureStay() { return getConfig().getInt("titles.departure.stay", 40); }
    public int getDepartureFadeOut() { return getIntConfig("titles.departure.fade-out", "titles.departure.fade_out", 5); }

    public boolean isWaitingTitleEnabled() { return getConfig().getBoolean("titles.waiting.enabled", true); }
    public String getWaitingTitle() { return getConfig().getString("titles.waiting.title", "Train Departing Soon"); }
    public String getWaitingSubtitle() { return getConfig().getString("titles.waiting.subtitle", ""); }
    public int getWaitingInterval() { return getConfig().getInt("titles.waiting.interval", 20); }

    public boolean isTerminalStopTitleEnabled() { return getBooleanConfig("titles.terminal-stop.enabled", "titles.terminal_stop.enabled", true); }
    public String getTerminalStopTitle() { return getStringConfig("titles.terminal-stop.title", "titles.terminal_stop.title", "&b{stop_name}"); }
    public String getTerminalStopSubtitle() { return getStringConfig("titles.terminal-stop.subtitle", "titles.terminal_stop.subtitle", "&cTerminal Station - Please Exit"); }
    public int getTerminalStopFadeIn() { return getIntConfig("titles.terminal-stop.fade-in", "titles.terminal_stop.fade_in", 10); }
    public int getTerminalStopStay() { return getConfig().getInt("titles.terminal-stop.stay", getConfig().getInt("titles.terminal_stop.stay", 60)); }
    public int getTerminalStopFadeOut() { return getIntConfig("titles.terminal-stop.fade-out", "titles.terminal_stop.fade_out", 10); }

    public String getWaitingActionbar() { return getStringConfig("actionbar.waiting", "titles.waiting.actionbar", ""); }
    public String getDepartureActionbar() { return getStringConfig("actionbar.departing", "titles.departure.actionbar", ""); }
    public int getArrivalInitialDelay() { return getConfig().getInt("sounds.arrival.initial_delay", 20); }
    public int getDepartureInitialDelay() { return getConfig().getInt("sounds.departure.initial_delay", 0); }
    public int getDepartureInterval() { return getIntConfig("titles.departure-interval", "titles.departure.interval", 40); }
    public List<String> getArrivalNotes() {
        List<String> notes = getConfig().getStringList("sounds.arrival.notes");
        if (!notes.isEmpty()) {
            return notes;
        }
        return List.of("NOTE,6,0.8,CHIME,0", "NOTE,10,0.9,CHIME,4", "NOTE,13,1.0,CHIME,8");
    }
    public List<String> getDepartureNotes() {
        List<String> notes = getConfig().getStringList("sounds.departure.notes");
        if (!notes.isEmpty()) {
            return notes;
        }
        return List.of("NOTE,18,1.0,PLING,0", "NOTE,18,1.0,PLING,4");
    }

    public boolean isArrivalSoundEnabled() { return isRailwaySoundEnabled("sounds.arrival.enabled", "sounds.arrival.notes"); }
    public boolean isDepartureSoundEnabled() { return isRailwaySoundEnabled("sounds.departure.enabled", "sounds.departure.notes"); }

    public boolean isTravelTimeEnabled() { return getConfig().getBoolean("travel-time.enabled", false); }
    public double getDefaultSectionSeconds() { return getConfig().getDouble("travel-time.default-section-seconds", 10.0); }
    public double getPriorStrength() { return getConfig().getDouble("travel-time.prior-strength", 3.0); }
    public double getOutlierSigma() { return getConfig().getDouble("travel-time.outlier-sigma", 4.0); }
    public double getDecayPerDay() { return getConfig().getDouble("travel-time.decay-per-day", 0.05); }
    public double getUnboardedSampleWeight() { return getConfig().getDouble("travel-time.unboarded-sample-weight", 0.5); }
    public boolean isUseUnboardedSamples() { return getConfig().getBoolean("travel-time.use-unboarded-samples", true); }

    public String getEntityTypeOverride() { return getConfig().getString("entity-model.entity-type-override", ""); }
    public String getServiceModeRaw() { return getConfig().getString("service.mode", "local"); }
    public double getCartSpeed() { return getDoubleConfig("cart-speed", "settings.cart_speed", 0.4); }
    public double getTrainSpacing() { return getConfig().getDouble("train-spacing", 3.0); }
    public int getServiceHeartbeatIntervalTicks() { return getConfig().getInt("service.heartbeat-interval-ticks", 2); }

    public org.cubexmc.metro.service.LineServiceManager getLineServiceManager() { return lineServiceManager; }

    public org.cubexmc.metro.model.EntityModelController getEntityModelController() { return entityModelController; }

    private boolean isRailwaySoundEnabled(String enabledPath, String notesPath) {
        if (!getConfig().contains(enabledPath)) {
            return true;
        }
        boolean enabled = getConfig().getBoolean(enabledPath, true);
        return enabled || !getConfig().contains(notesPath);
    }

    private String getStringConfig(String primaryPath, String legacyPath, String fallback) {
        return getConfig().contains(primaryPath)
                ? getConfig().getString(primaryPath, fallback)
                : getConfig().getString(legacyPath, fallback);
    }

    private boolean getBooleanConfig(String primaryPath, String legacyPath, boolean fallback) {
        return getConfig().contains(primaryPath)
                ? getConfig().getBoolean(primaryPath, fallback)
                : getConfig().getBoolean(legacyPath, fallback);
    }

    private int getIntConfig(String primaryPath, String legacyPath, int fallback) {
        return getConfig().contains(primaryPath)
                ? getConfig().getInt(primaryPath, fallback)
                : getConfig().getInt(legacyPath, fallback);
    }

    private double getDoubleConfig(String primaryPath, String legacyPath, double fallback) {
        return getConfig().contains(primaryPath)
                ? getConfig().getDouble(primaryPath, fallback)
                : getConfig().getDouble(legacyPath, fallback);
    }
}
