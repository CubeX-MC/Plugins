@file:Suppress("DEPRECATION")

package org.cubexmc.ecobalancer

import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.ComponentBuilder
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.permission.Permission
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.RegisteredServiceProvider
import org.cubexmc.config.MigrationException
import org.cubexmc.config.ResourceFiles
import org.cubexmc.core.CubexPlugin
import org.cubexmc.ecobalancer.commands.EcoTabCompleter
import org.cubexmc.ecobalancer.commands.UtilCommand
import org.cubexmc.ecobalancer.gui.GuiManager
import org.cubexmc.ecobalancer.integrations.EcoBalancerPlaceholderExpansion
import org.cubexmc.ecobalancer.listeners.AdminLoginListener
import org.cubexmc.ecobalancer.metrics.Metrics
import org.cubexmc.ecobalancer.policies.PolicyManager
import org.cubexmc.ecobalancer.policies.TaxPolicy
import org.cubexmc.ecobalancer.tax.DebtMode
import org.cubexmc.ecobalancer.tax.TaxContext
import org.cubexmc.ecobalancer.tax.TaxDecision
import org.cubexmc.ecobalancer.tax.TaxDecisionResult
import org.cubexmc.ecobalancer.tax.TaxLedgerService
import org.cubexmc.ecobalancer.tax.TaxOperationType
import org.cubexmc.ecobalancer.tax.TaxRunService
import org.cubexmc.ecobalancer.utils.AnalysisFilters
import org.cubexmc.ecobalancer.utils.ConfigMigrator
import org.cubexmc.ecobalancer.utils.DatabaseUtils
import org.cubexmc.ecobalancer.utils.EconomicMetrics
import org.cubexmc.ecobalancer.utils.MessageUtils
import org.cubexmc.ecobalancer.utils.PlaytimeUtils
import org.cubexmc.ecobalancer.utils.SchedulerUtils
import org.cubexmc.ecobalancer.utils.VaultUtils
import org.cubexmc.i18n.ColorMode
import org.cubexmc.i18n.I18nOptions
import org.cubexmc.i18n.I18nService
import org.cubexmc.i18n.I18nServices
import org.cubexmc.i18n.MissingKeyMode
import org.cubexmc.i18n.PlaceholderStyle
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.EnumSet
import java.util.Locale
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.max

class EcoBalancer : CubexPlugin() {
    private var fileHandler: FileHandler? = null
    private val fileLogger: Logger = Logger.getLogger("EcoBalancerFileLogger")
    private var recordRetentionDays = 0
    lateinit var langConfig: FileConfiguration
        private set
    private var i18n: I18nService? = null
    var isTaxAccountEnabled: Boolean = false
    var taxAccountName: String? = null
    var messagePrefix: String = ""
        private set
    private var messagePrefixTemplate: String? = null
    lateinit var guiManager: GuiManager
        private set
    lateinit var policyManager: PolicyManager
        private set
    var taxRunService: TaxRunService = TaxRunService()
        private set
    lateinit var taxLedgerService: TaxLedgerService
        private set
    var nextScheduledRunMillis: Long = 0
        private set

    override fun enablePlugin() {
        if (!setupEconomy()) {
            abortEnable(String.format("[%s] - Disabled due to no Vault dependency found!", description.name))
        }

        guiManager = GuiManager(this)
        taxRunService = TaxRunService()
        taxLedgerService = TaxLedgerService(this)
        setupPermissions()

        try {
            VaultUtils.setupEconomy(this)
        } catch (throwable: Throwable) {
            logger.log(Level.WARNING, "VaultUtils setup failed; continuing with core economy provider", throwable)
        }

        val resources = ResourceFiles(this)
        resources.saveIfMissing(Arrays.asList("config.yml", "lang/zh_CN.yml", "lang/en_US.yml"))
        reloadConfig()

        val migrator = ConfigMigrator(this)
        val lang = config.getString("language", "en_US") ?: "en_US"
        resources.saveIfMissing("lang/$lang.yml")
        try {
            migrator.migrateStartupFilesOrThrow(lang)
        } catch (exception: MigrationException) {
            logger.log(Level.SEVERE, "EcoBalancer migration failed.", exception)
            abortEnable("EcoBalancer migration failed; refusing to start to protect existing data.")
        }
        reloadConfig()

        policyManager = PolicyManager(this)
        policyManager.initialize()

        loadConfiguration()

        val databaseFile = File(dataFolder, "records.db")
        if (!databaseFile.exists()) {
            try {
                databaseFile.createNewFile()
            } catch (exception: IOException) {
                logger.severe("无法创建数据库文件: ${exception.message}")
            }
        }

        DatabaseUtils.initializeTables(this, logger)

        val initialDelay = calculateDelayForDaily(Calendar.getInstance(), 0, 0)
        val cleanupPeriod = 24L * 60L * 60L * 20L
        SchedulerUtils.runTaskTimer(this, Runnable { cleanupRecords() }, initialDelay, cleanupPeriod)

        if (config.getBoolean("file-logging", true)) {
            initFileLogger(true)
        }
        bind(Runnable { closeFileLoggerOnShutdown() })

        val metrics = Metrics(this, 20269)
        metrics.addCustomChart(Metrics.SimplePie("chart_id") { "My value" })

        val economy = econ
        val accountName = taxAccountName
        if (isTaxAccountEnabled && economy != null && accountName != null && !economy.hasAccount(accountName)) {
            economy.createPlayerAccount(accountName)
        }

        server.pluginManager.registerEvents(AdminLoginListener(this), this)
        if (server.pluginManager.getPlugin("PlaceholderAPI") != null) {
            try {
                EcoBalancerPlaceholderExpansion(this).register()
                logger.info("PlaceholderAPI expansion registered.")
            } catch (throwable: Throwable) {
                logger.log(Level.WARNING, "Failed to register PlaceholderAPI expansion", throwable)
            }
        }

        val util = UtilCommand(this)
        val command = getCommand("ecobal")
        if (command != null) {
            command.setExecutor(util)
            command.tabCompleter = EcoTabCompleter(this, util)
        } else {
            logger.severe("Command 'ecobal' not found in plugin.yml. Tab completer not registered.")
        }

        displayAsciiArt()
        logger.info("EcoBalancer enabled!")

        if (SchedulerUtils.isFolia()) {
            logger.info("Folia support is enabled!")
        } else {
            logger.info("Running on standard Bukkit/Spigot server")
        }

        try {
            val statsWorld = config.getString("stats-world", "") ?: ""
            PlaytimeUtils.loadAllAsync(this, statsWorld)
        } catch (throwable: Throwable) {
            logger.log(Level.WARNING, "Failed to start playtime preload task", throwable)
        }
    }

    override fun disablePlugin() {
    }

    private fun displayAsciiArt() {
        val asciiArt = arrayOf(
            "▓█████  ▄████▄   ▒█████   ▄▄▄▄    ▄▄▄       ██▓    ",
            "▓█   ▀ ▒██▀ ▀█  ▒██▒  ██▒▓█████▄ ▒████▄    ▓██▒    ",
            "▒███   ▒▓█    ▄ ▒██░  ██▒▒██▒ ▄██▒██  ▀█▄  ▒██░    ",
            "▒▓█  ▄ ▒▓▓▄ ▄██▒▒██   ██░▒██░█▀  ░██▄▄▄▄██ ▒██░    ",
            "░▒████▒▒ ▓███▀ ░░ ████▓▒░░▓█  ▀█▓ ▓█   ▓██▒░██████▒",
            "░░ ▒░ ░░ ░▒ ▒  ░░ ▒░▒░▒░ ░▒▓███▀▒ ▒▒   ▓▒█░░ ▒░▓  ░",
            " ░ ░  ░  ░  ▒     ░ ▒ Version: ${description.version}",
            "   ░   ░        ░ ░ ░ Author: ${description.authors[0]}",
            "   ░  ░░ ░          ░ Website: ${description.website}",
            "                      Powered by CubeX",
        )

        val ansiReset = "\u001B[0m"
        val ansiYellow = "\u001B[33m"
        val ansiRed = "\u001B[31m"
        val ansiWhite = "\u001B[37m"

        logger.info("")
        for (i in asciiArt.indices) {
            var line = asciiArt[i]
            if (i < 6) {
                line = ansiYellow + line + ansiReset
            } else if (i < 9) {
                line = ansiYellow + line.substring(0, 21) + ansiReset + line.substring(21) + ansiReset
            } else if (i == 9) {
                line = line.replace("Cube", ansiRed + "Cube" + ansiWhite).replace("X", ansiWhite + "X" + ansiReset)
            }
            logger.info(line)
        }
        logger.info("")
    }

    fun useTaxAccount(): Boolean = isTaxAccountEnabled

    val taxAccountBalance: String
        get() = String.format("%.2f", econ?.getBalance(taxAccountName) ?: 0.0)

    val taxAccountBalanceValue: Double
        get() {
            val accountName = taxAccountName
            val economy = econ
            if (!isTaxAccountEnabled || accountName.isNullOrEmpty() || economy == null) {
                return 0.0
            }
            return economy.getBalance(accountName)
        }

    fun loadConfiguration() {
        SchedulerUtils.cancelAllTasks(this)
        loadLangFile()
        updateFileLoggerFromConfig()
        recordRetentionDays = config.getInt("record-retention-days", 30)
        scheduleCheck(calculateNextDelay())
        scheduleDailySnapshot()
        isTaxAccountEnabled = config.getBoolean("tax-account", true)
        taxAccountName = if (isTaxAccountEnabled) config.getString("tax-account-name", "tax") else null
    }

    private fun loadLangFile() {
        val lang = config.getString("language", "en_US") ?: "en_US"
        logger.info("Loading language file: $lang")
        val langFile = File(dataFolder, "lang${File.separator}$lang.yml")
        if (!langFile.exists()) {
            saveResource("lang${File.separator}$lang.yml", false)
        }
        langConfig = YamlConfiguration.loadConfiguration(langFile)
        messagePrefixTemplate = langConfig.getString("prefix", "<gray>[<gold>EcoBalancer<gray>]<reset>")
        messagePrefix = MessageUtils.renderMiniMessage(messagePrefixTemplate, null, "")
        i18n = I18nServices.create(
            this,
            I18nOptions.create()
                .currentLocale(lang)
                .defaultLocale("en_US")
                .fallbackLocales(Arrays.asList("zh_CN"))
                .bundledLocales(Arrays.asList("en_US", "zh_CN"))
                .prefixToken("<prefix>")
                .placeholderStyles(EnumSet.of(PlaceholderStyle.MINIMESSAGE_TAG))
                .colorMode(ColorMode.MINIMESSAGE)
                .missingKeyMode(MissingKeyMode.RETURN_KEY),
        )
        i18n?.reload()
    }

    fun getFormattedMessage(path: String, placeholders: Map<String, String>?): String {
        val service = i18n
        if (service != null && (placeholders == null || placeholders.isEmpty()) && langConfig.getString(path) != null) {
            return service.message(path)
        }
        return MessageUtils.formatMessage(langConfig, path, placeholders, messagePrefix)
    }

    fun getFormattedMessage(
        path: String,
        placeholders: Map<String, String>?,
        clickablePlaceholders: Array<String>,
        clickableComponents: Array<TextComponent>,
    ): TextComponent =
        MessageUtils.formatComponent(langConfig, path, placeholders, clickablePlaceholders, clickableComponents, messagePrefix)

    private fun initFileLogger(rotateExisting: Boolean) {
        val logDir = File(dataFolder.toString() + File.separator + "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        if (rotateExisting) {
            val lockFile = File(dataFolder.toString() + File.separator + "logs" + File.separator + "latest.log.lck")
            if (lockFile.exists()) {
                lockFile.delete()
            }
            val existingLogFile = File(dataFolder.toString() + File.separator + "logs" + File.separator + "latest.log")
            if (existingLogFile.exists()) {
                compressExistingLogFile(existingLogFile)
            }
        }
        try {
            fileHandler = FileHandler(dataFolder.toString() + File.separator + "logs" + File.separator + "latest.log", true).also { handler ->
                handler.formatter = SimpleFormatter()
                fileLogger.addHandler(handler)
                fileLogger.useParentHandlers = false
            }
        } catch (exception: IOException) {
            logger.severe("Could not create the log file handler for EcoBalancer.")
            exception.printStackTrace()
        }
    }

    private fun updateFileLoggerFromConfig() {
        val enable = config.getBoolean("file-logging", true)
        if (enable) {
            if (fileHandler == null) {
                initFileLogger(false)
            }
        } else {
            closeFileHandlerDuringReload()
        }
    }

    private fun closeFileHandlerDuringReload() {
        val handler = fileHandler ?: return
        try {
            fileLogger.removeHandler(handler)
        } catch (throwable: Throwable) {
            logger.log(Level.FINE, "Failed to detach file handler during reload", throwable)
        }
        try {
            handler.close()
        } catch (throwable: Throwable) {
            logger.log(Level.FINE, "Failed to close file handler during reload", throwable)
        }
        fileHandler = null
    }

    private fun closeFileLoggerOnShutdown() {
        val handler = fileHandler
        if (handler != null) {
            handler.flush()
            fileLogger.removeHandler(handler)
            handler.close()
            fileHandler = null
        }

        val logFile = File(dataFolder.toString() + File.separator + "logs" + File.separator + "latest.log")
        if (logFile.exists()) {
            compressExistingLogFile(logFile)
        }
        logger.info("EcoBalancer disabled.")
    }

    private fun compressExistingLogFile(logFile: File) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd-HHmm")
        val timestamp = dateFormat.format(Date(logFile.lastModified()))
        val renamedLogFile = File(logFile.parent, "$timestamp.log")
        if (!logFile.renameTo(renamedLogFile)) {
            logger.severe("Could not rename the log file.")
            return
        }
        val compressedFile = File(renamedLogFile.parent, renamedLogFile.name + ".gz")
        try {
            GZIPOutputStream(FileOutputStream(compressedFile)).use { output ->
                Files.copy(renamedLogFile.toPath(), output)
            }
        } catch (exception: IOException) {
            logger.severe("Could not compress the log file: ${exception.message}")
        }
        if (!renamedLogFile.delete()) {
            logger.severe("Could not delete the original log file after compression.")
        }
    }

    private fun setupEconomy(): Boolean {
        if (server.pluginManager.getPlugin("Vault") == null) {
            logger.info("EcoBalancer disabled [plugin=null]")
            return false
        }
        val provider: RegisteredServiceProvider<Economy>? = server.servicesManager.getRegistration(Economy::class.java)
        if (provider == null) {
            logger.info("EcoBalancer disabled [rsp=null]")
            return false
        }
        econ = provider.provider
        logger.info("${econ != null}")
        return econ != null
    }

    private fun setupPermissions(): Boolean {
        try {
            val provider: RegisteredServiceProvider<Permission>? = server.servicesManager.getRegistration(Permission::class.java)
            if (provider == null) {
                perms = null
                logger.info("Vault permissions provider not found; offline tax exemptions will use online/op checks only.")
                return false
            }
            perms = provider.provider
            return perms != null
        } catch (throwable: Throwable) {
            perms = null
            logger.log(Level.WARNING, "Failed to initialize Vault permissions provider", throwable)
            return false
        }
    }

    @JvmOverloads
    fun checkBalance(sender: CommandSender?, currentTime: Long, player: OfflinePlayer, log: Boolean, isCheckAll: Boolean, operationId: Int, specifiedPolicy: TaxPolicy? = null, suppliedContext: TaxContext? = null) {
        val lastPlayed = player.lastPlayed
        val daysOffline = (currentTime - lastPlayed) / (1000 * 60 * 60 * 24)
        val economy = econ ?: return
        val balance = if (economy.hasAccount(player)) economy.getBalance(player) else 0.0

        if (isTaxAccountEnabled && taxAccountName != null && taxAccountName == player.name) return

        val policy = specifiedPolicy ?: policyManager.getActivePolicy() ?: return
        val placeholders = HashMap<String, String>()
        placeholders["player"] = player.name ?: "Unknown"
        placeholders["balance"] = String.format("%.2f", balance)
        placeholders["days_offline"] = daysOffline.toString()
        val operationType = if (isCheckAll) TaxOperationType.CHECK_ALL else TaxOperationType.CHECK_PLAYER
        val context = suppliedContext ?: TaxContext(operationId, policy.name ?: "default", operationType, currentTime, isCheckAll, null)
        val decision = calculateAndApplyDecision(sender, player, policy, context, daysOffline, placeholders, log) ?: return
        recordDecision(player, decision, isCheckAll, context)
    }

    private fun sendMessage(sender: CommandSender?, path: String, placeholders: Map<String, String>?, isLog: Boolean) {
        val message = getFormattedMessage(path, placeholders)
        if (sender != null) {
            for (line in message.split("\n")) {
                sender.sendMessage(line)
            }
        }
        if (isLog && config.getBoolean("file-logging", true) && fileHandler != null) {
            for (line in message.split("\n")) {
                fileLogger.info(line)
            }
        }
    }

    private fun sendTaxRunBusy(sender: CommandSender?) {
        val service = taxRunService
        if (sender == null) return
        val state = service.state
        val placeholders = hashMapOf(
            "operation_id" to state.operationId.toString(),
            "policy" to (state.policyName ?: ""),
            "processed" to state.processedPlayers.toString(),
            "total" to state.totalPlayers.toString(),
        )
        sender.sendMessage(getFormattedMessage("messages.tax.run_busy", placeholders))
    }

    private fun handleTaxRunFailure(operationId: Int, sender: CommandSender?, throwable: Throwable) {
        logger.log(Level.SEVERE, "Tax run #$operationId failed; releasing tax run state.", throwable)
        taxRunService?.finish()
        if (sender != null) {
            val placeholders = hashMapOf(
                "operation_id" to operationId.toString(),
                "error" to (throwable.message ?: throwable.javaClass.simpleName),
            )
            sender.sendMessage(getFormattedMessage("messages.tax.run_failed", placeholders))
        }
    }

    private fun calculateNextDelay(): Long {
        val now = Calendar.getInstance()
        val policy = policyManager.getActivePolicy() ?: return 20L * 60
        return when (policy.scheduleType) {
            "daily" -> calculateDelayForDaily(now, policy)
            "weekly" -> calculateDelayForWeekly(now, policy)
            "monthly" -> calculateDelayForMonthly(now, policy)
            else -> calculateDelayForDaily(now, policy)
        }
    }

    private fun calculateDelayForDaily(now: Calendar, policy: TaxPolicy): Long {
        val time = policy.checkTime
        val hourOfDay = time.split(":")[0].toInt()
        val minute = time.split(":")[1].toInt()
        return calculateDelayForDaily(now, hourOfDay, minute)
    }

    private fun calculateAndApplyDecision(
        sender: CommandSender?,
        player: OfflinePlayer,
        policy: TaxPolicy,
        context: TaxContext,
        daysOffline: Long,
        placeholders: MutableMap<String, String>,
        log: Boolean,
    ): TaxDecision? {
        val economy = econ ?: return null
        val oldBalance = if (economy.hasAccount(player)) economy.getBalance(player) else 0.0
        if (!economy.hasAccount(player)) {
            return TaxDecision(oldBalance, 0.0, 0.0, oldBalance, TaxDecisionResult.SKIPPED_NO_ACCOUNT, "no_vault_account")
        }

        if (isTaxExempt(player, policy, context.operationType)) {
            return TaxDecision(oldBalance, 0.0, 0.0, oldBalance, TaxDecisionResult.EXEMPT, "permission_exempt")
        }

        if (policy.isOnlyOfflinePlayers && player.isOnline) {
            return TaxDecision(oldBalance, 0.0, 0.0, oldBalance, TaxDecisionResult.SKIPPED_ONLINE, "policy_only_offline")
        }

        if (oldBalance < 0.0) {
            economy.depositPlayer(player, -1 * oldBalance)
            val newBalance = economy.getBalance(player)
            placeholders["new_balance"] = String.format("%.2f", newBalance)
            sendMessage(sender, "messages.negative_balance", placeholders, log)
            return TaxDecision(oldBalance, 0.0, oldBalance - newBalance, newBalance, TaxDecisionResult.NEGATIVE_BALANCE_FIXED, "negative_balance_fixed")
        }

        if (oldBalance <= 0.0) {
            sendMessage(sender, "messages.zero_balance", placeholders, log)
            return TaxDecision(oldBalance, 0.0, 0.0, oldBalance, TaxDecisionResult.ZERO_DEDUCTION, "zero_balance")
        }

        val daysToClear = policy.inactiveDaysToClear
        val daysToDeduct = policy.inactiveDaysToDeduct
        val timeCheck = daysToClear > 0 || daysToDeduct > 0

        if (timeCheck && daysToClear > 0 && daysOffline > daysToClear) {
            val actual = oldBalance
            economy.withdrawPlayer(player, actual)
            val accountName = taxAccountName
            if (isTaxAccountEnabled && accountName != null) economy.depositPlayer(accountName, actual)
            val newBalance = economy.getBalance(player)
            placeholders["new_balance"] = String.format("%.2f", newBalance)
            sendMessage(sender, "messages.offline_extreme", placeholders, log)
            return TaxDecision(oldBalance, actual, actual, newBalance, TaxDecisionResult.CLEARED, "inactive_clear")
        }

        if (timeCheck && (daysToDeduct <= 0 || daysOffline <= daysToDeduct)) {
            sendMessage(sender, "messages.offline_active", placeholders, false)
            return TaxDecision(oldBalance, 0.0, 0.0, oldBalance, TaxDecisionResult.SKIPPED_INACTIVE_DAYS, "inactive_days_not_reached")
        }

        val requestedDeduction = policy.calculateTax(oldBalance) { name -> policyManager.getPolicy(name) }
        val decision = applyDeductionWithDebtMode(player, oldBalance, requestedDeduction, policy, context)
        placeholders["deduction"] = String.format("%.2f", decision.actualDeduction)
        when (decision.result) {
            TaxDecisionResult.INSUFFICIENT_BALANCE_SKIPPED -> sendMessage(sender, "messages.tax.insufficient_balance_skipped", placeholders, log)
            TaxDecisionResult.ZERO_DEDUCTION -> sendMessage(sender, "messages.tax.zero_deduction", placeholders, log)
            else -> {
                if (timeCheck) sendMessage(sender, "messages.offline_moderate", placeholders, log)
                else sendMessage(sender, "messages.deduction_made", placeholders, log)
            }
        }
        runDebtCommandsIfNeeded(player, decision)
        return decision
    }

    private fun applyDeductionWithDebtMode(player: OfflinePlayer, oldBalance: Double, requestedDeduction: Double, policy: TaxPolicy, context: TaxContext): TaxDecision {
        val economy = econ ?: return TaxDecision(oldBalance, requestedDeduction, 0.0, oldBalance, TaxDecisionResult.ZERO_DEDUCTION, "economy_unavailable")
        if (requestedDeduction <= 0.0) {
            return TaxDecision(oldBalance, requestedDeduction, 0.0, oldBalance, TaxDecisionResult.ZERO_DEDUCTION, "calculated_tax_zero")
        }

        val mode = resolveDebtMode(policy)
        var actualDeduction = requestedDeduction
        var result = TaxDecisionResult.TAXED
        var reason = "taxed"

        if (oldBalance < requestedDeduction) {
            if (mode == DebtMode.SKIP) {
                return TaxDecision(oldBalance, requestedDeduction, 0.0, oldBalance, TaxDecisionResult.INSUFFICIENT_BALANCE_SKIPPED, "insufficient_balance_skip")
            }
            if (mode == DebtMode.DRAIN) {
                actualDeduction = max(0.0, oldBalance)
                result = TaxDecisionResult.DRAINED_TO_ZERO
                reason = "insufficient_balance_drain"
            } else {
                result = TaxDecisionResult.TAXED
                reason = "insufficient_balance_allow_negative"
            }
        }

        if (actualDeduction > 0) {
            economy.withdrawPlayer(player, actualDeduction)
            val accountName = taxAccountName
            if (isTaxAccountEnabled && accountName != null) economy.depositPlayer(accountName, actualDeduction)
        }
        val newBalance = economy.getBalance(player)
        val ledgerDecision = TaxDecision(oldBalance, requestedDeduction, actualDeduction, newBalance, result, reason)
        SchedulerUtils.runTaskAsync(this, Runnable { taxLedgerService.recordTax(player, context, ledgerDecision) })
        return ledgerDecision
    }

    private fun resolveDebtMode(policy: TaxPolicy): DebtMode {
        val global = DebtMode.fromConfig(config.getString("debt-mode", "skip"), DebtMode.SKIP)
        val policyMode = DebtMode.fromConfig(policy.debtMode, DebtMode.INHERIT)
        return if (policyMode == DebtMode.INHERIT) global else policyMode
    }

    private fun isTaxExempt(player: OfflinePlayer, policy: TaxPolicy?, operationType: TaxOperationType?): Boolean {
        if (!config.getBoolean("tax-exempt.enabled", true)) return false
        val globalPerm = config.getString("tax-exempt.global-permission", config.getString("tax-exempt-permission", "ecobalancer.exempt"))
        if (hasPermission(player, globalPerm)) return true
        val exemptPermission = policy?.exemptPermission
        if (!exemptPermission.isNullOrBlank() && hasPermission(player, exemptPermission)) return true
        val policyPrefix = config.getString("tax-exempt.policy-permission-prefix", "ecobalancer.exempt.policy")
        if (policy != null && hasPermission(player, "$policyPrefix.${policy.name}")) return true
        val operationPrefix = config.getString("tax-exempt.operation-permission-prefix", "ecobalancer.exempt.operation")
        return operationType != null && hasPermission(player, "$operationPrefix.${operationType.configKey}")
    }

    private fun hasPermission(player: OfflinePlayer?, permission: String?): Boolean {
        if (player == null || permission.isNullOrBlank()) return false
        try {
            val onlinePlayer = player.player
            if (player.isOnline && onlinePlayer != null && onlinePlayer.hasPermission(permission)) return true
            val permissionProvider = perms
            if (permissionProvider != null && Bukkit.getWorlds().isNotEmpty()) {
                return permissionProvider.playerHas(Bukkit.getWorlds()[0].name, player, permission)
            }
            return player.isOp
        } catch (throwable: Throwable) {
            logger.log(Level.FINE, "Failed to check tax exemption permission", throwable)
            return false
        }
    }

    private fun runDebtCommandsIfNeeded(player: OfflinePlayer, decision: TaxDecision?) {
        if (decision == null || decision.result != TaxDecisionResult.INSUFFICIENT_BALANCE_SKIPPED && decision.result != TaxDecisionResult.DRAINED_TO_ZERO) {
            return
        }
        val commands = config.getStringList("debt-commands")
        if (commands.isEmpty()) return
        val playerName = player.name ?: "Unknown"
        for (command in commands) {
            if (command.isNullOrBlank()) continue
            val parsed = command.replace("%player%", playerName)
                .replace("%requested%", String.format("%.2f", decision.requestedDeduction))
                .replace("%actual%", String.format("%.2f", decision.actualDeduction))
            SchedulerUtils.runTask(this, Runnable { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed) })
        }
    }

    private fun recordDecision(player: OfflinePlayer, decision: TaxDecision, isCheckAll: Boolean, context: TaxContext) {
        if (!config.getBoolean("record-zero-deduction", true) && abs(decision.actualDeduction) < 1e-9 && decision.result == TaxDecisionResult.ZERO_DEDUCTION) {
            return
        }
        saveRecord(player, decision, isCheckAll, context)
    }

    private fun calculateDelayForDaily(now: Calendar, hours: Int, minutes: Int): Long {
        val nextCheck = now.clone() as Calendar
        nextCheck.set(Calendar.HOUR_OF_DAY, hours)
        nextCheck.set(Calendar.MINUTE, minutes)
        nextCheck.set(Calendar.SECOND, 0)
        nextCheck.set(Calendar.MILLISECOND, 0)
        if (nextCheck.before(now)) {
            nextCheck.add(Calendar.DAY_OF_MONTH, 1)
        }
        return (nextCheck.timeInMillis - now.timeInMillis) / 50
    }

    private fun calculateDelayForWeekly(now: Calendar, policy: TaxPolicy): Long {
        var scheduleDaysOfWeek = policy.scheduleDaysOfWeek
        if (scheduleDaysOfWeek.isEmpty()) {
            scheduleDaysOfWeek = Collections.singletonList(1)
        }
        val today = now.get(Calendar.DAY_OF_WEEK)
        if (scheduleDaysOfWeek.contains(today)) {
            val delayForToday = calculateDelayForDaily(now, policy)
            if (delayForToday > 0) return delayForToday
        }
        val daysUntilNextCheck = scheduleDaysOfWeek.sorted()
            .filter { dayOfWeek -> dayOfWeek > today }
            .map { dayOfWeek -> dayOfWeek - today }
            .firstOrNull() ?: (7 + scheduleDaysOfWeek[0] - today)
        val time = policy.checkTime
        val hourOfDay = time.split(":")[0].toInt()
        val minute = time.split(":")[1].toInt()
        val nextCheck = now.clone() as Calendar
        nextCheck.add(Calendar.DAY_OF_WEEK, daysUntilNextCheck)
        nextCheck.set(Calendar.HOUR_OF_DAY, hourOfDay)
        nextCheck.set(Calendar.MINUTE, minute)
        nextCheck.set(Calendar.SECOND, 0)
        nextCheck.set(Calendar.MILLISECOND, 0)
        return (nextCheck.timeInMillis - now.timeInMillis) / 50
    }

    private fun calculateDelayForMonthly(now: Calendar, policy: TaxPolicy): Long {
        var scheduleDatesOfMonth = policy.scheduleDatesOfMonth
        if (scheduleDatesOfMonth.isEmpty()) {
            scheduleDatesOfMonth = Collections.singletonList(1)
        }
        val dayOfMonth = now.get(Calendar.DAY_OF_MONTH)
        if (scheduleDatesOfMonth.contains(dayOfMonth)) {
            val delayForToday = calculateDelayForDaily(now, policy)
            if (delayForToday > 0) return delayForToday
        }
        val daysUntilNextCheck = scheduleDatesOfMonth
            .filter { date -> date > dayOfMonth }
            .map { date -> date - dayOfMonth }
            .firstOrNull() ?: (scheduleDatesOfMonth[0] + now.getActualMaximum(Calendar.DAY_OF_MONTH) - dayOfMonth)
        val time = policy.checkTime
        val hourOfDay = time.split(":")[0].toInt()
        val minute = time.split(":")[1].toInt()
        val nextCheck = now.clone() as Calendar
        nextCheck.add(Calendar.DAY_OF_MONTH, daysUntilNextCheck)
        nextCheck.set(Calendar.HOUR_OF_DAY, hourOfDay)
        nextCheck.set(Calendar.MINUTE, minute)
        nextCheck.set(Calendar.SECOND, 0)
        nextCheck.set(Calendar.MILLISECOND, 0)
        return (nextCheck.timeInMillis - now.timeInMillis) / 50
    }

    private fun scheduleCheck(delay: Long) {
        nextScheduledRunMillis = System.currentTimeMillis() + max(0L, delay) * 50L
        SchedulerUtils.runTaskLater(this, Runnable {
            val active = policyManager.getActivePolicy()
            val service = taxRunService
            if (active == null || !active.isRoutine) {
                logger.info("Scheduled tax run skipped because the active policy is manual-only or missing.")
            } else if (service != null && service.isRunning()) {
                logger.info("Scheduled tax run skipped because another tax run is in progress.")
            } else {
                checkAll(null)
            }
            scheduleCheck(calculateNextDelay())
        }, delay)
    }

    fun checkPlayer(sender: CommandSender, playerName: String) {
        val target = Bukkit.getOfflinePlayer(playerName)
        if (target.hasPlayedBefore()) {
            val service = taxRunService
            if (service.isRunning()) {
                sendTaxRunBusy(sender)
                return
            }
            val currentTime = System.currentTimeMillis()
            computeMetricsSnapshotBatched(sender, 200, 1L) { before ->
                val operationId = getNextOperationId(false)
                val policy = policyManager.getActivePolicy()
                val policyName = policy?.name ?: "default"
                val runService = taxRunService
                if (!runService.tryStart(operationId, policyName, TaxOperationType.CHECK_PLAYER, 1, sender)) {
                    sendTaxRunBusy(sender)
                    return@computeMetricsSnapshotBatched
                }
                try {
                    checkBalance(sender, currentTime, target, true, false, operationId)
                    taxRunService.updateProgress(
                        1,
                        DatabaseUtils.getAffectedPlayersCount(this, operationId, logger),
                        DatabaseUtils.calculateTotalDeduction(this, operationId, logger),
                    )
                } finally {
                    taxRunService.finish()
                }
                saveImpactAfterDelay(operationId, before, 60L)
            }
        } else {
            sender.sendMessage(getFormattedMessage("messages.player_not_found", null))
        }
    }

    fun executePolicy(sender: CommandSender?, policyName: String) {
        executePolicy(sender, policyName, null)
    }

    fun executePolicy(sender: CommandSender?, policyName: String, criteriaInput: AnalysisFilters.FilterCriteria?) {
        val policy = policyManager.getPolicy(policyName)
        if (policy == null) {
            sender?.sendMessage(getFormattedMessage("messages.policy_not_found", mapOf("name" to policyName)))
            return
        }
        val service = taxRunService
        if (service.isRunning()) {
            sendTaxRunBusy(sender)
            return
        }

        val currentTime = System.currentTimeMillis()
        var criteria = criteriaInput
        if (criteria == null) {
            val filterStrFromCfg = config.getString("tax-filters", "")
            if (!filterStrFromCfg.isNullOrBlank()) {
                criteria = AnalysisFilters.parse(filterStrFromCfg.trim().split("\\s+".toRegex()).toTypedArray()).criteria
            }
        }
        val statsWorld = config.getString("stats-world", "") ?: ""
        val players = if (criteria == null) Arrays.asList(*Bukkit.getOfflinePlayers()) else AnalysisFilters.collectFilteredPlayers(criteria, statsWorld)
        val finalCriteria = criteria
        val batchSize = 100
        val delay = 10L
        val startPh = hashMapOf("policy" to policyName, "player_count" to players.size.toString())
        sender?.sendMessage(getFormattedMessage("messages.executing_policy", startPh))

        computeMetricsSnapshotBatched(sender, 200, 1L, players) { before ->
            val operationId = getNextOperationId(true)
            val runService = taxRunService
            if (!runService.startBatchedRun(
                    operationId,
                    policyName,
                    TaxOperationType.POLICY_EXECUTE,
                    players,
                    batchSize,
                    delay,
                    sender,
                    { task, delayTicks -> SchedulerUtils.runTaskLater(this, task, delayTicks) },
                    { player, _ ->
                        val context = TaxContext(operationId, policy.name ?: policyName, TaxOperationType.POLICY_EXECUTE, currentTime, true, finalCriteria)
                        checkBalance(null, currentTime, player, false, true, operationId, policy, context)
                    },
                    { start, end, total -> sendBatchProgress(sender, start, end, total) },
                    {
                        calculateTotalDeduction(operationId)
                        val totalDeduction = DatabaseUtils.calculateTotalDeduction(this, operationId, logger)
                        val affected = DatabaseUtils.getAffectedPlayersCount(this, operationId, logger)
                        taxRunService.updateProgress(players.size, affected, totalDeduction)
                        SchedulerUtils.runTask(this, Runnable {
                            val donePh = hashMapOf("policy" to policyName)
                            sender?.sendMessage(getFormattedMessage("messages.policy_executed", donePh))
                        })
                        saveImpactAfterDelay(operationId, before, 100L)
                    },
                    { failedOperationId, throwable -> handleTaxRunFailure(failedOperationId, sender, throwable) },
                )
            ) {
                sendTaxRunBusy(sender)
            }
        }
    }

    fun checkAll(sender: CommandSender?) {
        checkAll(sender, null)
    }

    fun checkAll(sender: CommandSender?, criteriaInput: AnalysisFilters.FilterCriteria?) {
        val service = taxRunService
        if (service.isRunning()) {
            sendTaxRunBusy(sender)
            return
        }
        val currentTime = System.currentTimeMillis()
        var criteria = criteriaInput
        if (criteria == null) {
            val filterStrFromCfg = config.getString("tax-filters", "")
            if (!filterStrFromCfg.isNullOrBlank()) {
                criteria = AnalysisFilters.parse(filterStrFromCfg.trim().split("\\s+".toRegex()).toTypedArray()).criteria
            }
        }
        val statsWorld = config.getString("stats-world", "") ?: ""
        val players = if (criteria == null) Arrays.asList(*Bukkit.getOfflinePlayers()) else AnalysisFilters.collectFilteredPlayers(criteria, statsWorld)
        val finalCriteria = criteria
        val batchSize = 100
        val delay = 10L

        computeMetricsSnapshotBatched(sender, 200, 1L, players) { before ->
            val operationId = getNextOperationId(true)
            val activePolicy = policyManager.getActivePolicy()
            val policyName = activePolicy?.name ?: "default"
            val trigger = if (sender == null) TaxOperationType.SCHEDULED else TaxOperationType.CHECK_ALL
            val runService = taxRunService
            if (!runService.startBatchedRun(
                    operationId,
                    policyName,
                    trigger,
                    players,
                    batchSize,
                    delay,
                    sender,
                    { task, delayTicks -> SchedulerUtils.runTaskLater(this, task, delayTicks) },
                    { player, _ ->
                        val currentPolicy = policyManager.getActivePolicy()
                        val context = TaxContext(operationId, currentPolicy?.name ?: "default", trigger, currentTime, true, finalCriteria)
                        checkBalance(null, currentTime, player, false, true, operationId, currentPolicy, context)
                    },
                    { start, end, total -> sendBatchProgress(sender, start, end, total) },
                    {
                        calculateTotalDeduction(operationId)
                        val totalDeduction = DatabaseUtils.calculateTotalDeduction(this, operationId, logger)
                        val affected = DatabaseUtils.getAffectedPlayersCount(this, operationId, logger)
                        taxRunService.updateProgress(players.size, affected, totalDeduction)
                        SchedulerUtils.runTask(this, Runnable { sendMessage(sender, "messages.all_players_processed", null, true) })
                        saveImpactAfterDelay(operationId, before, 100L)
                    },
                    { failedOperationId, throwable -> handleTaxRunFailure(failedOperationId, sender, throwable) },
                )
            ) {
                sendTaxRunBusy(sender)
            }
        }
    }

    private fun sendBatchProgress(sender: CommandSender?, start: Int, end: Int, totalPlayers: Int) {
        val placeholders = hashMapOf(
            "start" to start.toString(),
            "end" to end.toString(),
            "batch" to (end - start).toString(),
            "total_players" to totalPlayers.toString(),
        )
        sendMessage(sender, "messages.players_processing", placeholders, true)
    }

    private fun calculateTotalDeduction(operationId: Int) {
        val totalDeduction = DatabaseUtils.calculateTotalDeduction(this, operationId, logger)
        logger.info("Operation $operationId total deduction: $totalDeduction")
    }

    private fun getNextOperationId(isCheckAll: Boolean): Int = DatabaseUtils.getNextOperationId(this, isCheckAll, logger)

    fun generateHistogramFromBalances(sender: CommandSender, numBars: Int, balancesInput: List<Double>?, originalArgs: Array<String>?) {
        sender.sendMessage(getFormattedMessage("messages.stats_hist_drawing", null))
        val balances = balancesInput ?: ArrayList()
        val min = balances.minOrNull() ?: 0.0
        val maxValue = balances.maxOrNull() ?: 0.0
        var range = maxValue - min
        if (range <= 0) range = 1.0
        val barWidth = range / max(1, numBars)
        val placeholders = hashMapOf("min" to String.format("%.2f", min), "max" to String.format("%.2f", maxValue))
        sender.sendMessage(getFormattedMessage("messages.stats_min_max", placeholders))

        val histogram = IntArray(max(1, numBars))
        for (balance in balances) {
            var barIndex = ((balance - min) / barWidth).toInt()
            if (barIndex < 0) barIndex = 0
            if (barIndex >= histogram.size) barIndex = histogram.size - 1
            histogram[barIndex]++
        }

        val maxBarLength = 100
        val maxFrequency = histogram.maxOrNull() ?: 0
        sender.sendMessage(getFormattedMessage("messages.stats_hist_header", null))

        val base = StringBuilder("/ecobal interval")
        if (originalArgs != null) {
            for (token in originalArgs) {
                if (token.contains(":")) base.append(' ').append(token)
            }
        }

        for (i in histogram.indices) {
            val lowerBound = min + i * barWidth
            val upperBound = lowerBound + barWidth
            val barLength = if (maxFrequency > 0) (histogram[i].toDouble() / maxFrequency * maxBarLength).toInt() else 0
            val bar = "§a" + "▏".repeat(barLength) + "§r"
            val intervalPlaceholders = hashMapOf(
                "bar" to bar,
                "frequency" to histogram[i].toString(),
                "low" to EconomicMetrics.formatLargeNumber(lowerBound),
                "up" to EconomicMetrics.formatLargeNumber(upperBound),
            )
            val clickableBar = TextComponent(bar)
            val command = base.toString() + " l:" + String.format(Locale.ROOT, "%.6f", lowerBound) + " u:" + String.format(Locale.ROOT, "%.6f", upperBound) + " balance"
            clickableBar.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, command)
            clickableBar.hoverEvent = HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                ComponentBuilder(getFormattedMessage("messages.stats_check_interval", intervalPlaceholders)).create(),
            )
            val message = getFormattedMessage("messages.stats_bar", intervalPlaceholders, arrayOf("bar"), arrayOf(clickableBar))
            sender.spigot().sendMessage(message)
        }

        val mean = EconomicMetrics.calculateMean(balances)
        val median = EconomicMetrics.calculateMedian(EconomicMetrics.getSortedBalances(balances))
        val standardDeviation = EconomicMetrics.calculateStdDev(balances, mean)
        val statsPlaceholders = hashMapOf(
            "mean" to String.format("%.2f", mean),
            "median" to String.format("%.2f", median),
            "sd" to String.format("%.2f", standardDeviation),
        )
        sender.sendMessage(getFormattedMessage("messages.stats_mean_median", statsPlaceholders))
        sender.sendMessage(getFormattedMessage("messages.stats_sd", statsPlaceholders))
    }

    fun calculatePercentile(balance: Double, low: Double, high: Double): Double {
        val balances = ArrayList<Double>()
        val economy = econ ?: return 0.0
        for (player in Bukkit.getOfflinePlayers()) {
            if (economy.hasAccount(player)) {
                val playerBalance = economy.getBalance(player)
                if (playerBalance >= low && playerBalance <= high) {
                    balances.add(playerBalance)
                }
            }
        }
        val totalPlayers = balances.size
        val playersBelow = balances.count { it < balance }
        return playersBelow.toDouble() / totalPlayers * 100
    }

    private fun saveRecord(player: OfflinePlayer, oldBalance: Double, newBalance: Double, deduction: Double, isCheckAll: Boolean, operationId: Int) {
        SchedulerUtils.runTaskAsync(this, Runnable { DatabaseUtils.saveRecord(this, player, oldBalance, newBalance, deduction, isCheckAll, operationId, logger) })
    }

    private fun saveRecord(player: OfflinePlayer, decision: TaxDecision, isCheckAll: Boolean, context: TaxContext) {
        SchedulerUtils.runTaskAsync(
            this,
            Runnable {
                DatabaseUtils.saveRecord(
                    this,
                    player,
                    decision.oldBalance,
                    decision.newBalance,
                    decision.actualDeduction,
                    isCheckAll,
                    context.operationId,
                    context.policyName,
                    context.operationType?.configKey,
                    decision.result.name,
                    decision.reason,
                    decision.requestedDeduction,
                    decision.actualDeduction,
                    logger,
                )
            },
        )
    }

    private fun cleanupRecords() {
        DatabaseUtils.cleanupRecords(this, recordRetentionDays, logger)
    }

    private fun scheduleDailySnapshot() {
        try {
            val checkTime = config.getString("check-time", "00:00") ?: "00:00"
            val hourOfDay = checkTime.split(":")[0].toInt()
            val minute = checkTime.split(":")[1].toInt()
            val initialDelay = calculateDelayForDaily(Calendar.getInstance(), hourOfDay, minute)
            val dayPeriod = 24L * 60L * 60L * 20L
            SchedulerUtils.runTaskTimer(this, Runnable { createEconomicSnapshot() }, initialDelay, dayPeriod)
        } catch (throwable: Throwable) {
            logger.log(Level.WARNING, "Failed to schedule daily economic snapshot task", throwable)
        }
    }

    private fun createEconomicSnapshot() {
        try {
            val balances = collectAllBalances()
            val totalMoney = balances.sum()
            val playerCount = balances.size
            val sorted = ArrayList(balances)
            sorted.sort()
            val mean = if (playerCount > 0) totalMoney / playerCount else 0.0
            val median = EconomicMetrics.calculateMedian(sorted)
            val stdDev = EconomicMetrics.calculateStdDev(balances, mean)
            var gini = 0.0
            try {
                gini = EconomicMetrics.calculateGini(balances)
            } catch (throwable: Throwable) {
                logger.log(Level.FINE, "Failed to calculate snapshot gini", throwable)
            }
            var top1Pct = 0.0
            try {
                top1Pct = EconomicMetrics.calculateConcentration(balances, 1.0)
            } catch (throwable: Throwable) {
                logger.log(Level.FINE, "Failed to calculate snapshot top1 concentration", throwable)
            }
            var top5Pct = 0.0
            try {
                top5Pct = EconomicMetrics.calculateConcentration(balances, 5.0)
            } catch (throwable: Throwable) {
                logger.log(Level.FINE, "Failed to calculate snapshot top5 concentration", throwable)
            }
            var top10Pct = 0.0
            try {
                top10Pct = EconomicMetrics.calculateConcentration(balances, 10.0)
            } catch (throwable: Throwable) {
                logger.log(Level.FINE, "Failed to calculate snapshot top10 concentration", throwable)
            }

            var active7 = 0
            var active30 = 0
            try {
                val b7 = EconomicMetrics.collectBalances(7)
                val b30 = EconomicMetrics.collectBalances(30)
                active7 = b7?.size ?: 0
                active30 = b30?.size ?: 0
            } catch (throwable: Throwable) {
                logger.log(Level.FINE, "Failed to calculate active player counters for snapshot", throwable)
            }

            val date = SimpleDateFormat("yyyy-MM-dd").format(Date())
            val snap = DatabaseUtils.EconomicSnapshot()
            snap.date = date
            snap.timestamp = System.currentTimeMillis()
            snap.totalMoney = totalMoney
            snap.playerCount = playerCount
            snap.activePlayers7d = active7
            snap.activePlayers30d = active30
            snap.gini = gini
            snap.median = median
            snap.mean = mean
            snap.stdDev = stdDev
            snap.top1Pct = top1Pct
            snap.top5Pct = top5Pct
            snap.top10Pct = top10Pct
            DatabaseUtils.saveSnapshot(this, snap, logger)
        } catch (throwable: Throwable) {
            logger.warning("Failed to create economic snapshot: ${throwable.message}")
        }
    }

    private class MetricsSnapshot {
        var gini: Double = 0.0
        var median: Double = 0.0
        var mean: Double = 0.0
        var stdDev: Double = 0.0
        var top1Pct: Double = 0.0
        var totalMoney: Double = 0.0
    }

    private fun computeMetricsSnapshotBatched(sender: CommandSender?, batchSize: Int, delayTicks: Long, done: java.util.function.Consumer<MetricsSnapshot>) {
        computeMetricsSnapshotBatched(sender, batchSize, delayTicks, Arrays.asList(*Bukkit.getOfflinePlayers()), done)
    }

    private fun computeMetricsSnapshotBatched(sender: CommandSender?, batchSize: Int, delayTicks: Long, targets: List<OfflinePlayer>?, done: java.util.function.Consumer<MetricsSnapshot>) {
        val players = targets ?: emptyList()
        val balances = ArrayList<Double>(players.size)
        try {
            sender?.sendMessage(getFormattedMessage("messages.processing", null))
        } catch (throwable: Throwable) {
            logger.log(Level.FINE, "Failed to send processing message", throwable)
        }

        class PreScan : Runnable {
            var index = 0

            override fun run() {
                val start = index
                val end = minOf(index + max(1, batchSize), players.size)
                val economy = econ
                for (i in start until end) {
                    val player = players[i]
                    try {
                        if (economy != null && economy.hasAccount(player)) {
                            val balance = economy.getBalance(player)
                            if (balance >= 0) balances.add(balance)
                        }
                    } catch (throwable: Throwable) {
                        logger.log(Level.FINE, "Failed to collect balance for snapshot prescan", throwable)
                    }
                }
                index = end

                if (index < players.size) {
                    SchedulerUtils.runTaskLater(this@EcoBalancer, this, max(1L, delayTicks))
                } else {
                    val snapshot = MetricsSnapshot()
                    snapshot.totalMoney = balances.sum()
                    val n = balances.size
                    snapshot.mean = if (n > 0) snapshot.totalMoney / n else 0.0
                    val sorted = ArrayList(balances)
                    sorted.sort()
                    snapshot.median = EconomicMetrics.calculateMedian(sorted)
                    snapshot.stdDev = EconomicMetrics.calculateStdDev(balances, snapshot.mean)
                    try {
                        snapshot.gini = EconomicMetrics.calculateGini(balances)
                    } catch (throwable: Throwable) {
                        logger.log(Level.FINE, "Failed to compute prescan gini", throwable)
                    }
                    try {
                        snapshot.top1Pct = EconomicMetrics.calculateConcentration(balances, 1.0)
                    } catch (throwable: Throwable) {
                        logger.log(Level.FINE, "Failed to compute prescan top1 concentration", throwable)
                    }
                    done.accept(snapshot)
                }
            }
        }

        SchedulerUtils.runTask(this, PreScan())
    }

    private fun computeMetricsSnapshot(): MetricsSnapshot {
        val snapshot = MetricsSnapshot()
        val balances = collectAllBalances()
        snapshot.totalMoney = balances.sum()
        val n = balances.size
        snapshot.mean = if (n > 0) snapshot.totalMoney / n else 0.0
        val sorted = ArrayList(balances)
        sorted.sort()
        snapshot.median = EconomicMetrics.calculateMedian(sorted)
        snapshot.stdDev = EconomicMetrics.calculateStdDev(balances, snapshot.mean)
        try {
            snapshot.gini = EconomicMetrics.calculateGini(balances)
        } catch (throwable: Throwable) {
            logger.log(Level.FINE, "Failed to compute impact gini snapshot", throwable)
        }
        try {
            snapshot.top1Pct = EconomicMetrics.calculateConcentration(balances, 1.0)
        } catch (throwable: Throwable) {
            logger.log(Level.FINE, "Failed to compute impact concentration snapshot", throwable)
        }
        return snapshot
    }

    private fun saveImpactAfterDelay(operationId: Int, before: MetricsSnapshot, delayTicks: Long) {
        SchedulerUtils.runTaskLater(this, Runnable {
            val after = computeMetricsSnapshot()
            SchedulerUtils.runTaskAsync(this, Runnable {
                val impact = DatabaseUtils.OperationImpact()
                impact.operationId = operationId
                impact.beforeGini = before.gini
                impact.afterGini = after.gini
                impact.beforeMedian = before.median
                impact.afterMedian = after.median
                impact.beforeMean = before.mean
                impact.afterMean = after.mean
                impact.beforeStdDev = before.stdDev
                impact.afterStdDev = after.stdDev
                impact.beforeTop1Pct = before.top1Pct
                impact.afterTop1Pct = after.top1Pct
                impact.beforeTotalMoney = before.totalMoney
                impact.afterTotalMoney = after.totalMoney
                impact.totalTaxCollected = DatabaseUtils.calculateTotalDeduction(this, operationId, logger)
                impact.playersAffected = DatabaseUtils.getAffectedPlayersCount(this, operationId, logger)
                impact.timestamp = System.currentTimeMillis()
                DatabaseUtils.saveOperationImpact(this, operationId, impact, logger)
            })
        }, max(0L, delayTicks))
    }

    fun collectAllBalances(): List<Double> {
        val balances = ArrayList<Double>()
        val economy = econ
        try {
            for (player in Bukkit.getOfflinePlayers()) {
                try {
                    if (economy != null && economy.hasAccount(player)) {
                        balances.add(economy.getBalance(player))
                    }
                } catch (throwable: Throwable) {
                    logger.log(Level.FINE, "Failed to collect a player balance", throwable)
                }
            }
        } catch (throwable: Throwable) {
            logger.log(Level.WARNING, "Failed to enumerate offline players for balance collection", throwable)
        }
        return balances
    }

    fun saveCurrentConfiguration() {
        config.set("config-version", ConfigMigrator.CURRENT_CONFIG_VERSION)
        config.set("tax-account", isTaxAccountEnabled)
        config.set("tax-account-name", taxAccountName)
        saveConfig()
        logger.info("Configuration saved to config.yml")
    }

    companion object {
        private var econ: Economy? = null
        private var perms: Permission? = null

        @JvmStatic
        fun getEconomy(): Economy = econ ?: throw IllegalStateException("Economy provider is not initialized")
    }
}
