package org.cubexmc.ecobalancer;

import net.md_5.bungee.api.chat.*;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.cubexmc.core.CubexPlugin;
import org.cubexmc.config.MigrationException;
import org.cubexmc.config.ResourceFiles;
import org.cubexmc.ecobalancer.commands.*;
import org.cubexmc.i18n.ColorMode;
import org.cubexmc.i18n.I18nOptions;
import org.cubexmc.i18n.I18nService;
import org.cubexmc.i18n.I18nServices;
import org.cubexmc.i18n.MissingKeyMode;
import org.cubexmc.i18n.PlaceholderStyle;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import java.util.zip.GZIPOutputStream;

import org.cubexmc.ecobalancer.listeners.AdminLoginListener;
import org.cubexmc.ecobalancer.metrics.Metrics;
import org.cubexmc.ecobalancer.utils.SchedulerUtils;
import org.cubexmc.ecobalancer.utils.DatabaseUtils;
import org.cubexmc.ecobalancer.utils.MessageUtils;
import org.cubexmc.ecobalancer.utils.EconomicMetrics;
import org.cubexmc.ecobalancer.utils.PlaytimeUtils;
import org.cubexmc.ecobalancer.utils.ConfigMigrator;
import org.cubexmc.ecobalancer.utils.AnalysisFilters;
import org.cubexmc.ecobalancer.tax.DebtMode;
import org.cubexmc.ecobalancer.tax.TaxContext;
import org.cubexmc.ecobalancer.tax.TaxDecision;
import org.cubexmc.ecobalancer.tax.TaxDecisionResult;
import org.cubexmc.ecobalancer.tax.TaxLedgerService;
import org.cubexmc.ecobalancer.tax.TaxOperationType;
import org.cubexmc.ecobalancer.tax.TaxRunService;

@SuppressWarnings("deprecation")
public final class EcoBalancer extends CubexPlugin {
    private static Economy econ = null;
    private static Permission perms = null;
    private FileHandler fileHandler;
    private Logger fileLogger = Logger.getLogger("EcoBalancerFileLogger");
    private int recordRetentionDays;
    private FileConfiguration langConfig;
    private I18nService i18n;
    private boolean taxAccount;
    private String taxAccountName;
    private String messagePrefix;
    private String messagePrefixTemplate;
    private org.cubexmc.ecobalancer.gui.GuiManager guiManager;
    private org.cubexmc.ecobalancer.policies.PolicyManager policyManager;
    private TaxRunService taxRunService;
    private TaxLedgerService taxLedgerService;
    private long nextScheduledRunMillis;

    public org.cubexmc.ecobalancer.policies.PolicyManager getPolicyManager() {
        return policyManager;
    }

    public TaxRunService getTaxRunService() {
        return taxRunService;
    }

    public TaxLedgerService getTaxLedgerService() {
        return taxLedgerService;
    }

    public long getNextScheduledRunMillis() {
        return nextScheduledRunMillis;
    }

    private void initFileLogger(boolean rotateExisting) {
        // Create logs dir
        File logDir = new File(getDataFolder() + File.separator + "logs");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        // ... (lines omitted for brevity in tool call, but I will target specific
        // chunks)

        // Rotate previous log only on full startup to avoid churn on reload
        if (rotateExisting) {
            File lockFile = new File(getDataFolder() + File.separator + "logs" + File.separator + "latest.log.lck");
            if (lockFile.exists()) {
                lockFile.delete();
            }
            File existingLogFile = new File(getDataFolder() + File.separator + "logs" + File.separator + "latest.log");
            if (existingLogFile.exists()) {
                compressExistingLogFile(existingLogFile);
            }
        }
        try {
            fileHandler = new FileHandler(getDataFolder() + File.separator + "logs" + File.separator + "latest.log",
                    true);
            fileHandler.setFormatter(new SimpleFormatter());
            fileLogger.addHandler(fileHandler);
            fileLogger.setUseParentHandlers(false);
        } catch (IOException e) {
            getLogger().severe("Could not create the log file handler for EcoBalancer.");
            e.printStackTrace();
        }
    }

    private void updateFileLoggerFromConfig() {
        boolean enable = getConfig().getBoolean("file-logging", true);
        if (enable) {
            if (fileHandler == null) {
                initFileLogger(false);
            }
        } else {
            closeFileHandlerDuringReload();
        }
    }

    @Override
    protected void enablePlugin() {
        if (!setupEconomy()) {
            abortEnable(String.format("[%s] - Disabled due to no Vault dependency found!", getDescription().getName()));
        }

        // Initialize GUI Manager
        guiManager = new org.cubexmc.ecobalancer.gui.GuiManager(this);
        taxRunService = new TaxRunService();
        taxLedgerService = new TaxLedgerService(this);
        setupPermissions();

        // Initialize VaultUtils for modules that reference Vault via utility class
        try {
            org.cubexmc.ecobalancer.utils.VaultUtils.setupEconomy(this);
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "VaultUtils setup failed; continuing with core economy provider", t);
        }

        ResourceFiles resources = new ResourceFiles(this);
        resources.saveIfMissing(Arrays.asList("config.yml", "lang/zh_CN.yml", "lang/en_US.yml"));
        reloadConfig();

        // Run config and language migrations before loading runtime configuration
        ConfigMigrator migrator = new ConfigMigrator(this);
        String lang = getConfig().getString("language", "en_US");
        resources.saveIfMissing("lang/" + lang + ".yml");
        try {
            migrator.migrateStartupFilesOrThrow(lang);
        } catch (MigrationException e) {
            getLogger().log(Level.SEVERE, "EcoBalancer migration failed.", e);
            abortEnable("EcoBalancer migration failed; refusing to start to protect existing data.");
        }
        reloadConfig();

        // Initialize Policy Manager (Loads policies or creates default)
        policyManager = new org.cubexmc.ecobalancer.policies.PolicyManager(this);
        policyManager.initialize();

        loadConfiguration(); // 加载配置

        // 检查db，如果不存在则创建
        File dataFolder = getDataFolder();
        File databaseFile = new File(dataFolder, "records.db");

        // 如果数据库文件不存在,创建它
        if (!databaseFile.exists()) {
            try {
                databaseFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("无法创建数据库文件: " + e.getMessage());
            }
        }

        // 初始化数据库（建表、索引、PRAGMA）
        DatabaseUtils.initializeTables(this, getLogger());

        long initialDelay = calculateDelayForDaily(Calendar.getInstance(), 0, 0); // 在每天的午夜12点运行
        long cleanupPeriod = 24 * 60 * 60 * 20; // 24小时(以tick为单位)
        SchedulerUtils.runTaskTimer(this, this::cleanupRecords, initialDelay, cleanupPeriod);

        // Optional file logger based on config
        if (getConfig().getBoolean("file-logging", true)) {
            initFileLogger(true);
        }
        Runnable closeFileLogger = this::closeFileLoggerOnShutdown;
        bind(closeFileLogger);

        // metrics
        int pluginId = 20269; // <-- Replace with the id of your plugin!
        Metrics metrics = new Metrics(this, pluginId);
        // Optional: Add custom charts
        metrics.addCustomChart(new Metrics.SimplePie("chart_id", () -> "My value"));

        // 创建虚拟账户
        if (taxAccount && !econ.hasAccount(taxAccountName)) {
            econ.createPlayerAccount(taxAccountName);
        }

        getServer().getPluginManager().registerEvents(new AdminLoginListener(this), this);
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new org.cubexmc.ecobalancer.integrations.EcoBalancerPlaceholderExpansion(this).register();
                getLogger().info("PlaceholderAPI expansion registered.");
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "Failed to register PlaceholderAPI expansion", t);
            }
        }
        // Register executor and decoupled tab completer
        UtilCommand util = new UtilCommand(this);
        if (getCommand("ecobal") != null) {
            getCommand("ecobal").setExecutor(util);
            getCommand("ecobal").setTabCompleter(new org.cubexmc.ecobalancer.commands.EcoTabCompleter(this, util));
        } else {
            getLogger().severe("Command 'ecobal' not found in plugin.yml. Tab completer not registered.");
        }
        displayAsciiArt();
        getLogger().info("EcoBalancer enabled!");

        // 告知用户Folia支持状态
        if (SchedulerUtils.isFolia()) {
            getLogger().info("Folia support is enabled!");
        } else {
            getLogger().info("Running on standard Bukkit/Spigot server");
        }

        // 异步预加载 vanilla 统计数据（玩家在线时长）供 p:N 过滤使用
        try {
            String statsWorld = getConfig().getString("stats-world", "");
            PlaytimeUtils.loadAllAsync(this, statsWorld);
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Failed to start playtime preload task", t);
        }
    }

    private void displayAsciiArt() {
        String[] asciiArt = {
                "▓█████  ▄████▄   ▒█████   ▄▄▄▄    ▄▄▄       ██▓    ",
                "▓█   ▀ ▒██▀ ▀█  ▒██▒  ██▒▓█████▄ ▒████▄    ▓██▒    ",
                "▒███   ▒▓█    ▄ ▒██░  ██▒▒██▒ ▄██▒██  ▀█▄  ▒██░    ",
                "▒▓█  ▄ ▒▓▓▄ ▄██▒▒██   ██░▒██░█▀  ░██▄▄▄▄██ ▒██░    ",
                "░▒████▒▒ ▓███▀ ░░ ████▓▒░░▓█  ▀█▓ ▓█   ▓██▒░██████▒",
                "░░ ▒░ ░░ ░▒ ▒  ░░ ▒░▒░▒░ ░▒▓███▀▒ ▒▒   ▓▒█░░ ▒░▓  ░",
                " ░ ░  ░  ░  ▒     ░ ▒ Version: " + getDescription().getVersion(),
                "   ░   ░        ░ ░ ░ Author: " + getDescription().getAuthors().get(0),
                "   ░  ░░ ░          ░ Website: " + getDescription().getWebsite(),
                "                      Powered by CubeX"
        };

        // ANSI 转义序列for colors
        final String ANSI_RESET = "\u001B[0m";
        final String ANSI_YELLOW = "\u001B[33m";
        // Colors available if needed in future
        final String ANSI_RED = "\u001B[31m";
        final String ANSI_WHITE = "\u001B[37m";

        // 在控制台输出彩色的 ASCII 艺术字符
        getLogger().info("");
        for (int i = 0; i < asciiArt.length; i++) {
            String line = asciiArt[i];
            if (i < 6) {
                line = ANSI_YELLOW + line + ANSI_RESET;
            } else if (i < 9) {
                // green since 21 characters
                line = ANSI_YELLOW + line.substring(0, 21) + ANSI_RESET + line.substring(21) + ANSI_RESET;
            } else if (i == 9) {
                line = line.replace("Cube", ANSI_RED + "Cube" + ANSI_WHITE).replace("X", ANSI_WHITE + "X" + ANSI_RESET);
            }
            getLogger().info(line);
        }
        getLogger().info("");
    }

    public boolean useTaxAccount() {
        return taxAccount;
    }

    public String getTaxAccountName() {
        return taxAccountName;
    }

    public String getTaxAccountBalance() {
        return String.format("%.2f", econ.getBalance(taxAccountName));
    }

    public double getTaxAccountBalanceValue() {
        if (!taxAccount || taxAccountName == null || taxAccountName.isEmpty()) {
            return 0.0;
        }
        return econ.getBalance(taxAccountName);
    }

    public void loadConfiguration() {
        // Cancel all scheduled tasks
        SchedulerUtils.cancelAllTasks(this);
        // load language config
        loadLangFile();
        // Update file logger state on reload
        updateFileLoggerFromConfig();
        recordRetentionDays = getConfig().getInt("record-retention-days", 30);

        // Schedule checks using active policy
        scheduleCheck(calculateNextDelay());
        scheduleDailySnapshot();

        // Tax account settings are still global runtime settings.
        taxAccount = getConfig().getBoolean("tax-account", true);
        taxAccountName = taxAccount ? getConfig().getString("tax-account-name", "tax") : null;
    }

    private void loadLangFile() {
        // Load the language file based on config
        String lang = getConfig().getString("language", "en_US");
        getLogger().info("Loading language file: " + lang);
        File langFile = new File(getDataFolder(), "lang" + File.separator + lang + ".yml");
        if (!langFile.exists()) {
            saveResource("lang" + File.separator + lang + ".yml", false);
        }
        langConfig = YamlConfiguration.loadConfiguration(langFile);
        messagePrefixTemplate = langConfig.getString("prefix", "<gray>[<gold>EcoBalancer<gray>]<reset>");
        messagePrefix = MessageUtils.renderMiniMessage(messagePrefixTemplate, null, "");
        i18n = I18nServices.create(this, I18nOptions.create()
                .currentLocale(lang)
                .defaultLocale("en_US")
                .fallbackLocales(Arrays.asList("zh_CN"))
                .bundledLocales(Arrays.asList("en_US", "zh_CN"))
                .prefixToken("<prefix>")
                .placeholderStyles(EnumSet.of(PlaceholderStyle.MINIMESSAGE_TAG))
                .colorMode(ColorMode.MINIMESSAGE)
                .missingKeyMode(MissingKeyMode.RETURN_KEY));
        i18n.reload();
    }

    public String getFormattedMessage(String path, Map<String, String> placeholders) {
        if (i18n != null && (placeholders == null || placeholders.isEmpty()) && langConfig.getString(path) != null) {
            return i18n.message(path);
        }
        return MessageUtils.formatMessage(langConfig, path, placeholders, messagePrefix);
    }

    public TextComponent getFormattedMessage(String path, Map<String, String> placeholders,
            String[] clickablePlaceholders, TextComponent[] clickableComponents) {
        return MessageUtils.formatComponent(langConfig, path, placeholders, clickablePlaceholders, clickableComponents,
                messagePrefix);
    }

    /**
     * 获取语言配置
     * 
     * @return 语言配置
     */
    public FileConfiguration getLangConfig() {
        return langConfig;
    }

    /**
     * 获取消息前缀
     * 
     * @return 消息前缀
     */
    public String getMessagePrefix() {
        return messagePrefix;
    }

    @Override
    protected void disablePlugin() {
    }

    private void closeFileHandlerDuringReload() {
        if (fileHandler != null) {
            try {
                fileLogger.removeHandler(fileHandler);
            } catch (Throwable t) {
                getLogger().log(Level.FINE, "Failed to detach file handler during reload", t);
            }
            try {
                fileHandler.close();
            } catch (Throwable t) {
                getLogger().log(Level.FINE, "Failed to close file handler during reload", t);
            }
            fileHandler = null;
        }
    }

    private void closeFileLoggerOnShutdown() {
        // Ensure all pending logs are flushed and the handler is closed
        if (fileHandler != null) {
            fileHandler.flush();
            fileLogger.removeHandler(fileHandler);
            fileHandler.close();
            fileHandler = null;
        }

        // Now attempt to compress the log file
        File logFile = new File(getDataFolder() + File.separator + "logs" + File.separator + "latest.log");
        if (logFile.exists()) {
            compressExistingLogFile(logFile);
        }

        getLogger().info("EcoBalancer disabled.");
    }

    // Method to compress the existing log file
    private void compressExistingLogFile(File logFile) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HHmm");
        String timestamp = dateFormat.format(new Date(logFile.lastModified()));
        File renamedLogFile = new File(logFile.getParent(), timestamp + ".log");
        // Rename the file to include the timestamp
        if (!logFile.renameTo(renamedLogFile)) {
            getLogger().severe("Could not rename the log file.");
            return;
        }
        // Compress the renamed log file into a .gz file
        File compressedFile = new File(renamedLogFile.getParent(), renamedLogFile.getName() + ".gz");
        try (GZIPOutputStream gzos = new GZIPOutputStream(new FileOutputStream(compressedFile))) {
            Files.copy(renamedLogFile.toPath(), gzos);
        } catch (IOException e) {
            getLogger().severe("Could not compress the log file: " + e.getMessage());
        }
        // Delete the original (now renamed) log file after it's compressed
        if (!renamedLogFile.delete()) {
            getLogger().severe("Could not delete the original log file after compression.");
        }
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().info("EcoBalancer disabled [plugin=null]");
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().info("EcoBalancer disabled [rsp=null]");
            return false;
        }
        econ = rsp.getProvider();
        getLogger().info("" + (econ != null));
        return econ != null;
    }

    private boolean setupPermissions() {
        try {
            RegisteredServiceProvider<Permission> rsp = getServer().getServicesManager().getRegistration(Permission.class);
            if (rsp == null) {
                perms = null;
                getLogger().info("Vault permissions provider not found; offline tax exemptions will use online/op checks only.");
                return false;
            }
            perms = rsp.getProvider();
            return perms != null;
        } catch (Throwable t) {
            perms = null;
            getLogger().log(Level.WARNING, "Failed to initialize Vault permissions provider", t);
            return false;
        }
    }

    public static Economy getEconomy() {
        return econ;
    }

    public void checkBalance(CommandSender sender, long currentTime, OfflinePlayer player, boolean log,
            boolean isCheckAll, int operationId) {
        checkBalance(sender, currentTime, player, log, isCheckAll, operationId, null);
    }

    /**
     * Check and apply tax to a player's balance.
     * 
     * @param sender          The command sender (for messages)
     * @param currentTime     Current time in milliseconds
     * @param player          The player to check
     * @param log             Whether to log the result
     * @param isCheckAll      Whether this is part of a checkAll operation
     * @param operationId     The operation ID for tracking
     * @param specifiedPolicy The policy to use, or null to use the active policy
     */
    public void checkBalance(CommandSender sender, long currentTime, OfflinePlayer player, boolean log,
            boolean isCheckAll, int operationId, org.cubexmc.ecobalancer.policies.TaxPolicy specifiedPolicy) {
        checkBalance(sender, currentTime, player, log, isCheckAll, operationId, specifiedPolicy, null);
    }

    public void checkBalance(CommandSender sender, long currentTime, OfflinePlayer player, boolean log,
            boolean isCheckAll, int operationId, org.cubexmc.ecobalancer.policies.TaxPolicy specifiedPolicy,
            TaxContext suppliedContext) {
        long lastPlayed = player.getLastPlayed();
        long daysOffline = (currentTime - lastPlayed) / (1000 * 60 * 60 * 24);
        double balance = econ.hasAccount(player) ? econ.getBalance(player) : 0;

        if (taxAccount && taxAccountName != null && taxAccountName.equals(player.getName()))
            return;

        org.cubexmc.ecobalancer.policies.TaxPolicy policy = (specifiedPolicy != null) ? specifiedPolicy
                : policyManager.getActivePolicy();
        if (policy == null)
            return;

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName() != null ? player.getName() : "Unknown");
        placeholders.put("balance", String.format("%.2f", balance));
        placeholders.put("days_offline", String.valueOf(daysOffline));
        TaxOperationType operationType = isCheckAll ? TaxOperationType.CHECK_ALL : TaxOperationType.CHECK_PLAYER;
        TaxContext context = suppliedContext != null ? suppliedContext
                : new TaxContext(operationId, policy.getName(), operationType, currentTime, isCheckAll, null);
        TaxDecision decision = calculateAndApplyDecision(sender, player, policy, context, daysOffline, placeholders,
                log);

        if (decision == null) {
            return;
        }
        recordDecision(player, decision, isCheckAll, context);
    }

    private void sendMessage(CommandSender sender, String path, Map<String, String> placeholders, boolean isLog) {
        String message = getFormattedMessage(path, placeholders);
        if (sender != null)
            for (String str : message.split("\n"))
                sender.sendMessage(str);
        if (isLog && getConfig().getBoolean("file-logging", true) && fileHandler != null) {
            for (String str : message.split("\n"))
                fileLogger.info(str);
        }
    }

    private void sendTaxRunBusy(CommandSender sender) {
        if (sender == null || taxRunService == null) {
            return;
        }
        org.cubexmc.ecobalancer.tax.TaxRunState state = taxRunService.getState();
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("operation_id", String.valueOf(state.getOperationId()));
        placeholders.put("policy", state.getPolicyName() == null ? "" : state.getPolicyName());
        placeholders.put("processed", String.valueOf(state.getProcessedPlayers()));
        placeholders.put("total", String.valueOf(state.getTotalPlayers()));
        sender.sendMessage(getFormattedMessage("messages.tax.run_busy", placeholders));
    }

    private void handleTaxRunFailure(int operationId, CommandSender sender, Throwable t) {
        getLogger().log(Level.SEVERE, "Tax run #" + operationId + " failed; releasing tax run state.", t);
        if (taxRunService != null) {
            taxRunService.finish();
        }
        if (sender != null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("operation_id", String.valueOf(operationId));
            placeholders.put("error", t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            sender.sendMessage(getFormattedMessage("messages.tax.run_failed", placeholders));
        }
    }

    private long calculateNextDelay() {
        Calendar now = Calendar.getInstance();

        org.cubexmc.ecobalancer.policies.TaxPolicy p = policyManager.getActivePolicy();
        if (p == null)
            return 20L * 60;

        switch (p.getScheduleType()) {
            case "daily":
                return calculateDelayForDaily(now, p);
            case "weekly":
                return calculateDelayForWeekly(now, p);
            case "monthly":
                return calculateDelayForMonthly(now, p);
            default:
                return calculateDelayForDaily(now, p);
        }
    }

    private long calculateDelayForDaily(Calendar now, org.cubexmc.ecobalancer.policies.TaxPolicy p) {
        String time = p.getCheckTime();
        int hourOfDay = Integer.parseInt(time.split(":")[0]);
        int minute = Integer.parseInt(time.split(":")[1]);
        return calculateDelayForDaily(now, hourOfDay, minute);
    }

    private TaxDecision calculateAndApplyDecision(CommandSender sender, OfflinePlayer player,
            org.cubexmc.ecobalancer.policies.TaxPolicy policy, TaxContext context, long daysOffline,
            Map<String, String> placeholders, boolean log) {
        double oldBalance = econ.hasAccount(player) ? econ.getBalance(player) : 0.0;
        if (!econ.hasAccount(player)) {
            return new TaxDecision(oldBalance, 0.0, 0.0, oldBalance, TaxDecisionResult.SKIPPED_NO_ACCOUNT,
                    "no_vault_account");
        }

        if (isTaxExempt(player, policy, context.getOperationType())) {
            return new TaxDecision(oldBalance, 0.0, 0.0, oldBalance, TaxDecisionResult.EXEMPT,
                    "permission_exempt");
        }

        if (policy.isOnlyOfflinePlayers() && player.isOnline()) {
            return new TaxDecision(oldBalance, 0.0, 0.0, oldBalance, TaxDecisionResult.SKIPPED_ONLINE,
                    "policy_only_offline");
        }

        if (oldBalance < 0.0) {
            econ.depositPlayer(player, -1 * oldBalance);
            double newBalance = econ.getBalance(player);
            placeholders.put("new_balance", String.format("%.2f", newBalance));
            sendMessage(sender, "messages.negative_balance", placeholders, log);
            return new TaxDecision(oldBalance, 0.0, oldBalance - newBalance, newBalance,
                    TaxDecisionResult.NEGATIVE_BALANCE_FIXED, "negative_balance_fixed");
        }

        if (oldBalance <= 0.0) {
            sendMessage(sender, "messages.zero_balance", placeholders, log);
            return new TaxDecision(oldBalance, 0.0, 0.0, oldBalance, TaxDecisionResult.ZERO_DEDUCTION,
                    "zero_balance");
        }

        int daysToClear = policy.getInactiveDaysToClear();
        int daysToDeduct = policy.getInactiveDaysToDeduct();
        boolean timeCheck = daysToClear > 0 || daysToDeduct > 0;

        if (timeCheck && daysToClear > 0 && daysOffline > daysToClear) {
            double actual = oldBalance;
            econ.withdrawPlayer(player, actual);
            if (taxAccount)
                econ.depositPlayer(taxAccountName, actual);
            double newBalance = econ.getBalance(player);
            placeholders.put("new_balance", String.format("%.2f", newBalance));
            sendMessage(sender, "messages.offline_extreme", placeholders, log);
            return new TaxDecision(oldBalance, actual, actual, newBalance, TaxDecisionResult.CLEARED,
                    "inactive_clear");
        }

        if (timeCheck && (daysToDeduct <= 0 || daysOffline <= daysToDeduct)) {
            sendMessage(sender, "messages.offline_active", placeholders, false);
            return new TaxDecision(oldBalance, 0.0, 0.0, oldBalance, TaxDecisionResult.SKIPPED_INACTIVE_DAYS,
                    "inactive_days_not_reached");
        }

        double requestedDeduction = policy.calculateTax(oldBalance, policyManager::getPolicy);
        TaxDecision decision = applyDeductionWithDebtMode(player, oldBalance, requestedDeduction, policy, context);
        placeholders.put("deduction", String.format("%.2f", decision.getActualDeduction()));
        if (decision.getResult() == TaxDecisionResult.INSUFFICIENT_BALANCE_SKIPPED) {
            sendMessage(sender, "messages.tax.insufficient_balance_skipped", placeholders, log);
        } else if (decision.getResult() == TaxDecisionResult.ZERO_DEDUCTION) {
            sendMessage(sender, "messages.tax.zero_deduction", placeholders, log);
        } else if (timeCheck) {
            sendMessage(sender, "messages.offline_moderate", placeholders, log);
        } else {
            sendMessage(sender, "messages.deduction_made", placeholders, log);
        }
        runDebtCommandsIfNeeded(player, decision);
        return decision;
    }

    private TaxDecision applyDeductionWithDebtMode(OfflinePlayer player, double oldBalance, double requestedDeduction,
            org.cubexmc.ecobalancer.policies.TaxPolicy policy, TaxContext context) {
        if (requestedDeduction <= 0.0) {
            return new TaxDecision(oldBalance, requestedDeduction, 0.0, oldBalance, TaxDecisionResult.ZERO_DEDUCTION,
                    "calculated_tax_zero");
        }

        DebtMode mode = resolveDebtMode(policy);
        double actualDeduction = requestedDeduction;
        TaxDecisionResult result = TaxDecisionResult.TAXED;
        String reason = "taxed";

        if (oldBalance < requestedDeduction) {
            if (mode == DebtMode.SKIP) {
                return new TaxDecision(oldBalance, requestedDeduction, 0.0, oldBalance,
                        TaxDecisionResult.INSUFFICIENT_BALANCE_SKIPPED, "insufficient_balance_skip");
            }
            if (mode == DebtMode.DRAIN) {
                actualDeduction = Math.max(0.0, oldBalance);
                result = TaxDecisionResult.DRAINED_TO_ZERO;
                reason = "insufficient_balance_drain";
            } else {
                result = TaxDecisionResult.TAXED;
                reason = "insufficient_balance_allow_negative";
            }
        }

        if (actualDeduction > 0) {
            econ.withdrawPlayer(player, actualDeduction);
            if (taxAccount)
                econ.depositPlayer(taxAccountName, actualDeduction);
        }
        double newBalance = econ.getBalance(player);
        if (context != null && taxLedgerService != null) {
            TaxDecision ledgerDecision = new TaxDecision(oldBalance, requestedDeduction, actualDeduction, newBalance,
                    result, reason);
            SchedulerUtils.runTaskAsync(this, () -> taxLedgerService.recordTax(player, context, ledgerDecision));
        }
        return new TaxDecision(oldBalance, requestedDeduction, actualDeduction, newBalance, result, reason);
    }

    private DebtMode resolveDebtMode(org.cubexmc.ecobalancer.policies.TaxPolicy policy) {
        DebtMode global = DebtMode.fromConfig(getConfig().getString("debt-mode", "skip"), DebtMode.SKIP);
        DebtMode policyMode = DebtMode.fromConfig(policy.getDebtMode(), DebtMode.INHERIT);
        return policyMode == DebtMode.INHERIT ? global : policyMode;
    }

    private boolean isTaxExempt(OfflinePlayer player, org.cubexmc.ecobalancer.policies.TaxPolicy policy,
            TaxOperationType operationType) {
        if (!getConfig().getBoolean("tax-exempt.enabled", true)) {
            return false;
        }
        String globalPerm = getConfig().getString("tax-exempt.global-permission",
                getConfig().getString("tax-exempt-permission", "ecobalancer.exempt"));
        if (hasPermission(player, globalPerm)) {
            return true;
        }
        if (policy != null && policy.getExemptPermission() != null && !policy.getExemptPermission().trim().isEmpty()
                && hasPermission(player, policy.getExemptPermission())) {
            return true;
        }
        String policyPrefix = getConfig().getString("tax-exempt.policy-permission-prefix",
                "ecobalancer.exempt.policy");
        if (policy != null && hasPermission(player, policyPrefix + "." + policy.getName())) {
            return true;
        }
        String operationPrefix = getConfig().getString("tax-exempt.operation-permission-prefix",
                "ecobalancer.exempt.operation");
        return operationType != null && hasPermission(player, operationPrefix + "." + operationType.getConfigKey());
    }

    private boolean hasPermission(OfflinePlayer player, String permission) {
        if (player == null || permission == null || permission.trim().isEmpty()) {
            return false;
        }
        try {
            if (player.isOnline() && player.getPlayer() != null && player.getPlayer().hasPermission(permission)) {
                return true;
            }
            if (perms != null && !Bukkit.getWorlds().isEmpty()) {
                return perms.playerHas(Bukkit.getWorlds().get(0).getName(), player, permission);
            }
            return player.isOp();
        } catch (Throwable t) {
            getLogger().log(Level.FINE, "Failed to check tax exemption permission", t);
            return false;
        }
    }

    private void runDebtCommandsIfNeeded(OfflinePlayer player, TaxDecision decision) {
        if (decision == null || decision.getResult() != TaxDecisionResult.INSUFFICIENT_BALANCE_SKIPPED
                && decision.getResult() != TaxDecisionResult.DRAINED_TO_ZERO) {
            return;
        }
        List<String> commands = getConfig().getStringList("debt-commands");
        if (commands == null || commands.isEmpty()) {
            return;
        }
        String playerName = player.getName() == null ? "Unknown" : player.getName();
        for (String command : commands) {
            if (command == null || command.trim().isEmpty()) {
                continue;
            }
            String parsed = command.replace("%player%", playerName)
                    .replace("%requested%", String.format("%.2f", decision.getRequestedDeduction()))
                    .replace("%actual%", String.format("%.2f", decision.getActualDeduction()));
            SchedulerUtils.runTask(this, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed));
        }
    }

    private void recordDecision(OfflinePlayer player, TaxDecision decision, boolean isCheckAll, TaxContext context) {
        if (!getConfig().getBoolean("record-zero-deduction", true)
                && Math.abs(decision.getActualDeduction()) < 1e-9
                && decision.getResult() == TaxDecisionResult.ZERO_DEDUCTION) {
            return;
        }
        saveRecord(player, decision, isCheckAll, context);
    }

    private long calculateDelayForDaily(Calendar now, int hours, int minutes) {

        Calendar nextCheck = (Calendar) now.clone();
        nextCheck.set(Calendar.HOUR_OF_DAY, hours);
        nextCheck.set(Calendar.MINUTE, minutes);
        nextCheck.set(Calendar.SECOND, 0);
        nextCheck.set(Calendar.MILLISECOND, 0);

        // 如果下一个检查时间在现在之前，添加一天
        if (nextCheck.before(now)) {
            nextCheck.add(Calendar.DAY_OF_MONTH, 1);
        }

        return (nextCheck.getTimeInMillis() - now.getTimeInMillis()) / 50; // 返回ticks
    }

    private long calculateDelayForWeekly(Calendar now, org.cubexmc.ecobalancer.policies.TaxPolicy p) {
        List<Integer> scheduleDaysOfWeek = p.getScheduleDaysOfWeek();
        if (scheduleDaysOfWeek == null || scheduleDaysOfWeek.isEmpty()) {
            scheduleDaysOfWeek = java.util.Collections.singletonList(1);
        }
        int today = now.get(Calendar.DAY_OF_WEEK);
        if (scheduleDaysOfWeek.contains(today)) {
            // 如果还没到计划时间，返回今天的延迟
            long delayForToday = calculateDelayForDaily(now, p);
            if (delayForToday > 0) {
                return delayForToday;
            }
        }

        int daysUntilNextCheck = scheduleDaysOfWeek.stream()
                .sorted()
                .filter(dayOfWeek -> dayOfWeek > today)
                .map(dayOfWeek -> dayOfWeek - today)
                .findFirst()
                .orElse(7 + scheduleDaysOfWeek.get(0) - today);

        String time = p.getCheckTime();
        int hourOfDay = Integer.parseInt(time.split(":")[0]);
        int minute = Integer.parseInt(time.split(":")[1]);

        Calendar nextCheck = (Calendar) now.clone();
        nextCheck.add(Calendar.DAY_OF_WEEK, daysUntilNextCheck);
        nextCheck.set(Calendar.HOUR_OF_DAY, hourOfDay);
        nextCheck.set(Calendar.MINUTE, minute);
        nextCheck.set(Calendar.SECOND, 0);
        nextCheck.set(Calendar.MILLISECOND, 0);

        return (nextCheck.getTimeInMillis() - now.getTimeInMillis()) / 50; // 返回ticks
    }

    private long calculateDelayForMonthly(Calendar now, org.cubexmc.ecobalancer.policies.TaxPolicy p) {
        List<Integer> scheduleDatesOfMonth = p.getScheduleDatesOfMonth();
        if (scheduleDatesOfMonth == null || scheduleDatesOfMonth.isEmpty()) {
            scheduleDatesOfMonth = java.util.Collections.singletonList(1);
        }
        int dayOfMonth = now.get(Calendar.DAY_OF_MONTH);
        if (scheduleDatesOfMonth.contains(dayOfMonth)) {
            // 如果还没到计划时间，返回今天的延迟
            long delayForToday = calculateDelayForDaily(now, p);
            if (delayForToday > 0) {
                return delayForToday;
            }
        }
        int daysUntilNextCheck = scheduleDatesOfMonth.stream()
                .filter(date -> date > dayOfMonth)
                .map(date -> date - dayOfMonth)
                .findFirst()
                .orElse(scheduleDatesOfMonth.get(0) + now.getActualMaximum(Calendar.DAY_OF_MONTH) - dayOfMonth);

        String time = p.getCheckTime();
        int hourOfDay = Integer.parseInt(time.split(":")[0]);
        int minute = Integer.parseInt(time.split(":")[1]);

        Calendar nextCheck = (Calendar) now.clone();
        nextCheck.add(Calendar.DAY_OF_MONTH, daysUntilNextCheck);
        nextCheck.set(Calendar.HOUR_OF_DAY, hourOfDay);
        nextCheck.set(Calendar.MINUTE, minute);
        nextCheck.set(Calendar.SECOND, 0);
        nextCheck.set(Calendar.MILLISECOND, 0);

        return (nextCheck.getTimeInMillis() - now.getTimeInMillis()) / 50; // 返回ticks
    }

    private void scheduleCheck(long delay) {
        nextScheduledRunMillis = System.currentTimeMillis() + Math.max(0L, delay) * 50L;
        SchedulerUtils.runTaskLater(this, () -> {
            org.cubexmc.ecobalancer.policies.TaxPolicy active = policyManager.getActivePolicy();
            if (active == null || !active.isRoutine()) {
                getLogger().info("Scheduled tax run skipped because the active policy is manual-only or missing.");
            } else if (taxRunService != null && taxRunService.isRunning()) {
                getLogger().info("Scheduled tax run skipped because another tax run is in progress.");
            } else {
                checkAll(null); // 运行任务
            }

            // 任务完成后，计划下一个任务
            scheduleCheck(calculateNextDelay());
        }, delay);
    }

    public void checkPlayer(CommandSender sender, String playerName) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        if (target.hasPlayedBefore()) {
            if (taxRunService != null && taxRunService.isRunning()) {
                sendTaxRunBusy(sender);
                return;
            }
            long currentTime = System.currentTimeMillis();
            // Batched capture of metrics before the operation
            computeMetricsSnapshotBatched(sender, 200, 1L, before -> {
                final int operationId = getNextOperationId(false); // false for checkPlayer
                org.cubexmc.ecobalancer.policies.TaxPolicy policy = policyManager.getActivePolicy();
                String policyName = policy == null ? "default" : policy.getName();
                if (taxRunService != null
                        && !taxRunService.tryStart(operationId, policyName, TaxOperationType.CHECK_PLAYER, 1, sender)) {
                    sendTaxRunBusy(sender);
                    return;
                }
                try {
                    checkBalance(sender, currentTime, target, true, false, operationId);
                    taxRunService.updateProgress(1, DatabaseUtils.getAffectedPlayersCount(this, operationId, getLogger()),
                            DatabaseUtils.calculateTotalDeduction(this, operationId, getLogger()));
                } finally {
                    if (taxRunService != null)
                        taxRunService.finish();
                }
                // Persist impact after a short delay to allow async record saves to complete
                saveImpactAfterDelay(operationId, before, 60L);
            });
        } else {
            sender.sendMessage(getFormattedMessage("messages.player_not_found", null));
        }
    }

    public void checkAll(CommandSender sender) {
        checkAll(sender, null);
    }

    /**
     * Execute a specific policy on all players.
     * This allows running a non-active policy without changing the active policy
     * setting.
     * 
     * @param sender     The command sender
     * @param policyName The name of the policy to execute
     */
    public void executePolicy(CommandSender sender, String policyName) {
        executePolicy(sender, policyName, null);
    }

    /**
     * Execute a specific policy on all players with optional filters.
     * 
     * @param sender     The command sender
     * @param policyName The name of the policy to execute
     * @param criteria   Optional filter criteria
     */
    public void executePolicy(CommandSender sender, String policyName, AnalysisFilters.FilterCriteria criteria) {
        org.cubexmc.ecobalancer.policies.TaxPolicy policy = policyManager.getPolicy(policyName);
        if (policy == null) {
            if (sender != null) {
                sender.sendMessage(getFormattedMessage("messages.policy_not_found",
                        java.util.Collections.singletonMap("name", policyName)));
            }
            return;
        }
        if (taxRunService != null && taxRunService.isRunning()) {
            sendTaxRunBusy(sender);
            return;
        }

        final long currentTime = System.currentTimeMillis();
        // Resolve filters: from parameter or from config tax-filters
        String filterStrFromCfg = null;
        if (criteria == null) {
            filterStrFromCfg = getConfig().getString("tax-filters", "");
            if (filterStrFromCfg != null && !filterStrFromCfg.trim().isEmpty()) {
                criteria = AnalysisFilters.parse(filterStrFromCfg.trim().split("\\s+")).criteria;
            }
        }
        final String statsWorld = getConfig().getString("stats-world", "");
        final List<OfflinePlayer> players = (criteria == null)
                ? Arrays.asList(Bukkit.getOfflinePlayers())
                : AnalysisFilters.collectFilteredPlayers(criteria, statsWorld);
        final AnalysisFilters.FilterCriteria finalCriteria = criteria;
        final int batchSize = 100;
        final int delay = 10;

        Map<String, String> startPh = new HashMap<>();
        startPh.put("policy", policyName);
        startPh.put("player_count", String.valueOf(players.size()));
        if (sender != null) {
            sender.sendMessage(getFormattedMessage("messages.executing_policy", startPh));
        }

        computeMetricsSnapshotBatched(sender, 200, 1L, players, before -> {
            final int operationId = getNextOperationId(true);
            final org.cubexmc.ecobalancer.policies.TaxPolicy finalPolicy = policy;
            if (taxRunService == null || !taxRunService.startBatchedRun(operationId, policyName,
                    TaxOperationType.POLICY_EXECUTE, players, batchSize, delay, sender,
                    (task, delayTicks) -> SchedulerUtils.runTaskLater(EcoBalancer.this, task, delayTicks),
                    (player, index) -> {
                        TaxContext context = new TaxContext(operationId, finalPolicy.getName(),
                                TaxOperationType.POLICY_EXECUTE, currentTime, true, finalCriteria);
                        checkBalance(null, currentTime, player, false, true, operationId, finalPolicy, context);
                    },
                    (start, end, total) -> sendBatchProgress(sender, start, end, total),
                    () -> {
                        calculateTotalDeduction(operationId);
                        double totalDeduction = DatabaseUtils.calculateTotalDeduction(EcoBalancer.this, operationId,
                                getLogger());
                        int affected = DatabaseUtils.getAffectedPlayersCount(EcoBalancer.this, operationId,
                                getLogger());
                        taxRunService.updateProgress(players.size(), affected, totalDeduction);
                        SchedulerUtils.runTask(EcoBalancer.this, () -> {
                            Map<String, String> donePh = new HashMap<>();
                            donePh.put("policy", policyName);
                            if (sender != null) {
                                sender.sendMessage(getFormattedMessage("messages.policy_executed", donePh));
                            }
                        });
                        saveImpactAfterDelay(operationId, before, 100L);
                    },
                    (failedOperationId, t) -> handleTaxRunFailure(failedOperationId, sender, t))) {
                sendTaxRunBusy(sender);
            }
        });
    }

    public void checkAll(CommandSender sender, AnalysisFilters.FilterCriteria criteria) {
        if (taxRunService != null && taxRunService.isRunning()) {
            sendTaxRunBusy(sender);
            return;
        }
        final long currentTime = System.currentTimeMillis();
        // Resolve filters: from parameter or from config tax-filters
        String filterStrFromCfg = null;
        if (criteria == null) {
            filterStrFromCfg = getConfig().getString("tax-filters", "");
            if (filterStrFromCfg != null && !filterStrFromCfg.trim().isEmpty()) {
                criteria = AnalysisFilters.parse(filterStrFromCfg.trim().split("\\s+")).criteria;
            }
        }
        final String statsWorld = getConfig().getString("stats-world", "");
        final List<OfflinePlayer> players = (criteria == null)
                ? Arrays.asList(Bukkit.getOfflinePlayers())
                : AnalysisFilters.collectFilteredPlayers(criteria, statsWorld);
        final AnalysisFilters.FilterCriteria finalCriteria = criteria;
        final int batchSize = 100; // Number of players to process at once
        final int delay = 10; // Delay in ticks between batches (20 ticks = 1 second)

        // Batched compute of the "before" snapshot (on the filtered player set), then
        // proceed with taxation batches
        // IMPORTANT: when percentiles are in use for tax-brackets, they should be
        // computed
        // on the same population as the operation (filtered or global). Here we ensure
        // that
        // if config enables percentile-thresholds, we rebuild taxBrackets using the
        // current
        // criteria before running the taxation batches.

        computeMetricsSnapshotBatched(sender, 200, 1L, players, before -> {
            final int operationId = getNextOperationId(true);
            org.cubexmc.ecobalancer.policies.TaxPolicy activePolicy = policyManager.getActivePolicy();
            String policyName = activePolicy == null ? "default" : activePolicy.getName();
            TaxOperationType trigger = sender == null ? TaxOperationType.SCHEDULED : TaxOperationType.CHECK_ALL;
            if (taxRunService == null || !taxRunService.startBatchedRun(operationId, policyName, trigger, players,
                    batchSize, delay, sender,
                    (task, delayTicks) -> SchedulerUtils.runTaskLater(EcoBalancer.this, task, delayTicks),
                    (player, index) -> {
                        org.cubexmc.ecobalancer.policies.TaxPolicy currentPolicy = policyManager.getActivePolicy();
                        TaxContext context = new TaxContext(operationId,
                                currentPolicy == null ? "default" : currentPolicy.getName(), trigger, currentTime,
                                true, finalCriteria);
                        checkBalance(null, currentTime, player, false, true, operationId, currentPolicy, context);
                    },
                    (start, end, total) -> sendBatchProgress(sender, start, end, total),
                    () -> {
                        calculateTotalDeduction(operationId);
                        double totalDeduction = DatabaseUtils.calculateTotalDeduction(EcoBalancer.this, operationId,
                                getLogger());
                        int affected = DatabaseUtils.getAffectedPlayersCount(EcoBalancer.this, operationId,
                                getLogger());
                        taxRunService.updateProgress(players.size(), affected, totalDeduction);
                        SchedulerUtils.runTask(EcoBalancer.this, () -> {
                            sendMessage(sender, "messages.all_players_processed", null, true);
                        });
                        saveImpactAfterDelay(operationId, before, 100L);
                    },
                    (failedOperationId, t) -> handleTaxRunFailure(failedOperationId, sender, t))) {
                sendTaxRunBusy(sender);
            }
        });
    }

    private void sendBatchProgress(CommandSender sender, int start, int end, int totalPlayers) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("start", Integer.toString(start));
        placeholders.put("end", Integer.toString(end));
        placeholders.put("batch", Integer.toString(end - start));
        placeholders.put("total_players", Integer.toString(totalPlayers));
        sendMessage(sender, "messages.players_processing", placeholders, true);
    }

    private void calculateTotalDeduction(int operationId) {
        double totalDeduction = DatabaseUtils.calculateTotalDeduction(this, operationId, getLogger());
        getLogger().info("Operation " + operationId + " total deduction: " + totalDeduction);
    }

    private int getNextOperationId(boolean isCheckAll) {
        return DatabaseUtils.getNextOperationId(this, isCheckAll, getLogger());
    }

    public void generateHistogramFromBalances(CommandSender sender, int numBars, List<Double> balances,
            String[] originalArgs) {

        sender.sendMessage(getFormattedMessage("messages.stats_hist_drawing", null));
        if (balances == null)
            balances = new ArrayList<>();

        double min = balances.stream().min(Double::compareTo).orElse(0.0);
        double max = balances.stream().max(Double::compareTo).orElse(0.0);
        double range = max - min;
        if (range <= 0)
            range = 1.0;
        double barWidth = range / Math.max(1, numBars);
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("min", String.format("%.2f", min));
        placeholders.put("max", String.format("%.2f", max));
        sender.sendMessage(getFormattedMessage("messages.stats_min_max", placeholders));

        int[] histogram = new int[Math.max(1, numBars)];
        for (double balance : balances) {
            int barIndex = (int) ((balance - min) / barWidth);
            if (barIndex < 0)
                barIndex = 0;
            if (barIndex >= histogram.length)
                barIndex = histogram.length - 1;
            histogram[barIndex]++;
        }

        int maxBarLength = 100;
        int maxFrequency = Arrays.stream(histogram).max().orElse(0);

        sender.sendMessage(getFormattedMessage("messages.stats_hist_header", null));

        // Build filter token string for /ecobal interval
        StringBuilder base = new StringBuilder("/ecobal interval");
        if (originalArgs != null) {
            for (String tok : originalArgs) {
                if (tok != null && tok.contains(":"))
                    base.append(' ').append(tok);
            }
        }

        for (int i = 0; i < histogram.length; i++) {
            double lowerBound = min + i * barWidth;
            double upperBound = lowerBound + barWidth;
            int barLength = (maxFrequency > 0) ? (int) (((double) histogram[i] / maxFrequency) * maxBarLength) : 0;
            String bar = "§a" + "▏".repeat(barLength) + "§r";

            Map<String, String> intervalPlaceholders = new HashMap<>();
            intervalPlaceholders.put("bar", bar);
            intervalPlaceholders.put("frequency", Integer.toString(histogram[i]));
            intervalPlaceholders.put("low", EconomicMetrics.formatLargeNumber(lowerBound));
            intervalPlaceholders.put("up", EconomicMetrics.formatLargeNumber(upperBound));

            TextComponent clickableBar = new TextComponent(bar);
            String cmd = base.toString() + " l:" + String.format(java.util.Locale.ROOT, "%.6f", lowerBound) + " u:"
                    + String.format(java.util.Locale.ROOT, "%.6f", upperBound) + " balance";
            clickableBar.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd));
            clickableBar.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder(getFormattedMessage("messages.stats_check_interval", intervalPlaceholders))
                            .create()));

            TextComponent message = getFormattedMessage("messages.stats_bar", intervalPlaceholders,
                    new String[] { "bar" }, new TextComponent[] { clickableBar });
            sender.spigot().sendMessage(message);
        }

        double mean = EconomicMetrics.calculateMean(balances);
        double median = EconomicMetrics.calculateMedian(EconomicMetrics.getSortedBalances(balances));
        double standardDeviation = EconomicMetrics.calculateStdDev(balances, mean);

        Map<String, String> statsPlaceholders = new HashMap<>();
        statsPlaceholders.put("mean", String.format("%.2f", mean));
        statsPlaceholders.put("median", String.format("%.2f", median));
        statsPlaceholders.put("sd", String.format("%.2f", standardDeviation));
        sender.sendMessage(getFormattedMessage("messages.stats_mean_median", statsPlaceholders));
        sender.sendMessage(getFormattedMessage("messages.stats_sd", statsPlaceholders));
    }

    // Removed local median/stddev/format helpers in favor of EconomicMetrics

    public double calculatePercentile(double balance, double low, double high) {
        OfflinePlayer[] players = Bukkit.getOfflinePlayers();
        List<Double> balances = new ArrayList<>();

        for (OfflinePlayer player : players) {
            if (econ.hasAccount(player)) {
                double playerBalance = econ.getBalance(player);
                if (playerBalance >= low && playerBalance <= high) {
                    balances.add(playerBalance);
                }
            }
        }

        int totalPlayers = balances.size();
        int playersBelow = (int) balances.stream().filter(b -> b < balance).count();

        return (double) playersBelow / totalPlayers * 100;
    }

    private void saveRecord(OfflinePlayer player, double oldBalance, double newBalance, double deduction,
            boolean isCheckAll, int operationId) {
        // 在异步线程写库，避免阻塞主线程
        SchedulerUtils.runTaskAsync(this, () -> DatabaseUtils.saveRecord(this, player, oldBalance, newBalance,
                deduction, isCheckAll, operationId, getLogger()));
    }

    private void saveRecord(OfflinePlayer player, TaxDecision decision, boolean isCheckAll, TaxContext context) {
        SchedulerUtils.runTaskAsync(this,
                () -> DatabaseUtils.saveRecord(this, player, decision.getOldBalance(), decision.getNewBalance(),
                        decision.getActualDeduction(), isCheckAll, context.getOperationId(), context.getPolicyName(),
                        context.getOperationType() == null ? null : context.getOperationType().getConfigKey(),
                        decision.getResult().name(), decision.getReason(), decision.getRequestedDeduction(),
                        decision.getActualDeduction(), getLogger()));
    }

    private void cleanupRecords() {
        DatabaseUtils.cleanupRecords(this, recordRetentionDays, getLogger());
    }

    // Snapshot scheduling and computation
    private void scheduleDailySnapshot() {
        try {
            String checkTime = getConfig().getString("check-time", "00:00");
            int hourOfDay = Integer.parseInt(checkTime.split(":")[0]);
            int minute = Integer.parseInt(checkTime.split(":")[1]);
            long initialDelay = calculateDelayForDaily(Calendar.getInstance(), hourOfDay, minute);
            long dayPeriod = 24L * 60L * 60L * 20L; // 24h in ticks
            SchedulerUtils.runTaskTimer(this, this::createEconomicSnapshot, initialDelay, dayPeriod);
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Failed to schedule daily economic snapshot task", t);
        }
    }

    private void createEconomicSnapshot() {
        try {
            // Ensure on main thread for Vault access
            List<Double> balances = collectAllBalances();
            if (balances == null)
                balances = new ArrayList<>();
            double totalMoney = balances.stream().mapToDouble(Double::doubleValue).sum();
            int playerCount = balances.size();

            List<Double> sorted = new ArrayList<>(balances);
            Collections.sort(sorted);
            double mean = (playerCount > 0) ? (totalMoney / playerCount) : 0.0;
            double median = EconomicMetrics.calculateMedian(sorted);
            double stdDev = EconomicMetrics.calculateStdDev(balances, mean);
            double gini = 0.0;
            try {
                gini = org.cubexmc.ecobalancer.utils.EconomicMetrics.calculateGini(balances);
            } catch (Throwable t) {
                getLogger().log(Level.FINE, "Failed to calculate snapshot gini", t);
            }
            double top1Pct = 0.0;
            try {
                top1Pct = org.cubexmc.ecobalancer.utils.EconomicMetrics.calculateConcentration(balances, 1.0);
            } catch (Throwable t) {
                getLogger().log(Level.FINE, "Failed to calculate snapshot top1 concentration", t);
            }
            double top5Pct = 0.0;
            try {
                top5Pct = org.cubexmc.ecobalancer.utils.EconomicMetrics.calculateConcentration(balances, 5.0);
            } catch (Throwable t) {
                getLogger().log(Level.FINE, "Failed to calculate snapshot top5 concentration", t);
            }
            double top10Pct = 0.0;
            try {
                top10Pct = org.cubexmc.ecobalancer.utils.EconomicMetrics.calculateConcentration(balances, 10.0);
            } catch (Throwable t) {
                getLogger().log(Level.FINE, "Failed to calculate snapshot top10 concentration", t);
            }

            // Active players counts
            int active7 = 0;
            int active30 = 0;
            try {
                List<Double> b7 = org.cubexmc.ecobalancer.utils.EconomicMetrics.collectBalances(7);
                List<Double> b30 = org.cubexmc.ecobalancer.utils.EconomicMetrics.collectBalances(30);
                active7 = (b7 == null) ? 0 : b7.size();
                active30 = (b30 == null) ? 0 : b30.size();
            } catch (Throwable t) {
                getLogger().log(Level.FINE, "Failed to calculate active player counters for snapshot", t);
            }

            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd");
            String date = fmt.format(new Date());
            DatabaseUtils.EconomicSnapshot snap = new DatabaseUtils.EconomicSnapshot();
            snap.date = date;
            snap.timestamp = System.currentTimeMillis();
            snap.totalMoney = totalMoney;
            snap.playerCount = playerCount;
            snap.activePlayers7d = active7;
            snap.activePlayers30d = active30;
            snap.gini = gini;
            snap.median = median;
            snap.mean = mean;
            snap.stdDev = stdDev;
            snap.top1Pct = top1Pct;
            snap.top5Pct = top5Pct;
            snap.top10Pct = top10Pct;

            DatabaseUtils.saveSnapshot(this, snap, getLogger());
        } catch (Throwable t) {
            getLogger().warning("Failed to create economic snapshot: " + t.getMessage());
        }
    }

    private static class MetricsSnapshot {
        double gini;
        double median;
        double mean;
        double stdDev;
        double top1Pct;
        double totalMoney;
    }

    // Batched computation of metrics snapshot on the main thread to avoid long
    // stalls (all players)
    private void computeMetricsSnapshotBatched(CommandSender sender, int batchSize, long delayTicks,
            java.util.function.Consumer<MetricsSnapshot> done) {
        computeMetricsSnapshotBatched(sender, batchSize, delayTicks,
                java.util.Arrays.asList(Bukkit.getOfflinePlayers()), done);
    }

    // Batched computation of metrics snapshot for a target player list (e.g.,
    // filtered set)
    private void computeMetricsSnapshotBatched(CommandSender sender, int batchSize, long delayTicks,
            java.util.List<OfflinePlayer> targets, java.util.function.Consumer<MetricsSnapshot> done) {
        final java.util.List<OfflinePlayer> players = (targets == null) ? java.util.Collections.emptyList() : targets;
        final java.util.List<Double> balances = new java.util.ArrayList<>(players.size());
        try {
            if (sender != null)
                sender.sendMessage(getFormattedMessage("messages.processing", null));
        } catch (Throwable t) {
            getLogger().log(Level.FINE, "Failed to send processing message", t);
        }

        class PreScan implements Runnable {
            int index = 0;

            @Override
            public void run() {
                int start = index;
                int end = Math.min(index + Math.max(1, batchSize), players.size());
                for (int i = start; i < end; i++) {
                    OfflinePlayer p = players.get(i);
                    try {
                        if (econ != null && econ.hasAccount(p)) {
                            double bal = econ.getBalance(p);
                            if (bal >= 0)
                                balances.add(bal);
                        }
                    } catch (Throwable t) {
                        getLogger().log(Level.FINE, "Failed to collect balance for snapshot prescan", t);
                    }
                }
                index = end;

                if (index < players.size()) {
                    SchedulerUtils.runTaskLater(EcoBalancer.this, this, Math.max(1L, delayTicks));
                } else {
                    MetricsSnapshot ms = new MetricsSnapshot();
                    ms.totalMoney = balances.stream().mapToDouble(Double::doubleValue).sum();
                    int n = balances.size();
                    ms.mean = (n > 0) ? (ms.totalMoney / n) : 0.0;
                    java.util.List<Double> sorted = new java.util.ArrayList<>(balances);
                    java.util.Collections.sort(sorted);
                    ms.median = EconomicMetrics.calculateMedian(sorted);
                    ms.stdDev = EconomicMetrics.calculateStdDev(balances, ms.mean);
                    try {
                        ms.gini = EconomicMetrics.calculateGini(balances);
                    } catch (Throwable t) {
                        getLogger().log(Level.FINE, "Failed to compute prescan gini", t);
                    }
                    try {
                        ms.top1Pct = EconomicMetrics.calculateConcentration(balances, 1.0);
                    } catch (Throwable t) {
                        getLogger().log(Level.FINE, "Failed to compute prescan top1 concentration", t);
                    }
                    done.accept(ms);
                }
            }
        }

        SchedulerUtils.runTask(this, new PreScan());
    }

    private MetricsSnapshot computeMetricsSnapshot() {
        MetricsSnapshot ms = new MetricsSnapshot();
        List<Double> balances = collectAllBalances();
        if (balances == null)
            balances = new ArrayList<>();
        ms.totalMoney = balances.stream().mapToDouble(Double::doubleValue).sum();
        int n = balances.size();
        ms.mean = (n > 0) ? (ms.totalMoney / n) : 0.0;
        List<Double> sorted = new ArrayList<>(balances);
        Collections.sort(sorted);
        ms.median = EconomicMetrics.calculateMedian(sorted);
        ms.stdDev = EconomicMetrics.calculateStdDev(balances, ms.mean);
        try {
            ms.gini = org.cubexmc.ecobalancer.utils.EconomicMetrics.calculateGini(balances);
        } catch (Throwable t) {
            getLogger().log(Level.FINE, "Failed to compute impact gini snapshot", t);
        }
        try {
            ms.top1Pct = org.cubexmc.ecobalancer.utils.EconomicMetrics.calculateConcentration(balances, 1.0);
        } catch (Throwable t) {
            getLogger().log(Level.FINE, "Failed to compute impact concentration snapshot", t);
        }
        return ms;
    }

    // Save operation impact after a short delay to wait for async DB record writes
    private void saveImpactAfterDelay(int operationId, MetricsSnapshot before, long delayTicks) {
        SchedulerUtils.runTaskLater(this, () -> {
            // Compute after snapshot on main thread for Vault safety
            MetricsSnapshot after = computeMetricsSnapshot();
            // Write impact data asynchronously
            SchedulerUtils.runTaskAsync(this, () -> {
                DatabaseUtils.OperationImpact impact = new DatabaseUtils.OperationImpact();
                impact.operationId = operationId;
                impact.beforeGini = before.gini;
                impact.afterGini = after.gini;
                impact.beforeMedian = before.median;
                impact.afterMedian = after.median;
                impact.beforeMean = before.mean;
                impact.afterMean = after.mean;
                impact.beforeStdDev = before.stdDev;
                impact.afterStdDev = after.stdDev;
                impact.beforeTop1Pct = before.top1Pct;
                impact.afterTop1Pct = after.top1Pct;
                impact.beforeTotalMoney = before.totalMoney;
                impact.afterTotalMoney = after.totalMoney;
                impact.totalTaxCollected = DatabaseUtils.calculateTotalDeduction(this, operationId, getLogger());
                impact.playersAffected = DatabaseUtils.getAffectedPlayersCount(this, operationId, getLogger());
                impact.timestamp = System.currentTimeMillis();
                DatabaseUtils.saveOperationImpact(this, operationId, impact, getLogger());
            });
        }, Math.max(0L, delayTicks));
    }

    // Collect all player balances (offline + online) via Vault
    public List<Double> collectAllBalances() {
        List<Double> balances = new ArrayList<>();
        try {
            OfflinePlayer[] players = Bukkit.getOfflinePlayers();
            for (OfflinePlayer player : players) {
                try {
                    if (econ != null && econ.hasAccount(player)) {
                        double bal = econ.getBalance(player);
                        balances.add(bal);
                    }
                } catch (Throwable t) {
                    getLogger().log(Level.FINE, "Failed to collect a player balance", t);
                }
            }
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Failed to enumerate offline players for balance collection", t);
        }
        return balances;
    }

    // =====================================================
    // Getters for TaxCommand
    // =====================================================

    public boolean isTaxAccountEnabled() {
        return taxAccount;
    }

    // =====================================================
    // Setters for TaxCommand (runtime configuration)
    // =====================================================

    // Most setters removed as TaxCommand now operates on Active Policy

    public void setTaxAccountEnabled(boolean enabled) {
        this.taxAccount = enabled;
        // Create account if being enabled and doesn't exist
        if (enabled && taxAccountName != null && !taxAccountName.isEmpty()) {
            if (!econ.hasAccount(taxAccountName)) {
                econ.createPlayerAccount(taxAccountName);
            }
        }
    }

    public void setTaxAccountName(String name) {
        this.taxAccountName = name;
    }

    // =====================================================
    // Save current configuration to config.yml
    // =====================================================

    public org.cubexmc.ecobalancer.gui.GuiManager getGuiManager() {
        return guiManager;
    }

    public void saveCurrentConfiguration() {
        getConfig().set("config-version", org.cubexmc.ecobalancer.utils.ConfigMigrator.CURRENT_CONFIG_VERSION);
        getConfig().set("tax-account", taxAccount);
        getConfig().set("tax-account-name", taxAccountName);

        saveConfig();
        getLogger().info("Configuration saved to config.yml");
    }
}
