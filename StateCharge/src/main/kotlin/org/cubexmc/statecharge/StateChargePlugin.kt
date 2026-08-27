package org.cubexmc.statecharge

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.cubexmc.config.ConfigReload
import org.cubexmc.config.MigrationContext
import org.cubexmc.config.MigrationException
import org.cubexmc.config.MigrationPlan
import org.cubexmc.config.MigrationRunner
import org.cubexmc.config.MigrationStep
import org.cubexmc.config.ReloadChain
import org.cubexmc.config.ReloadFailurePolicy
import org.cubexmc.config.ReloadReport
import org.cubexmc.config.ResourceFiles
import org.cubexmc.core.CubexPlugin
import org.cubexmc.core.Reloadable
import org.cubexmc.economy.EconomyAccount
import org.cubexmc.economy.VaultEconomy
import org.cubexmc.scheduler.CubexScheduler
import org.cubexmc.scheduler.CubexTask
import org.cubexmc.statecharge.command.StateChargeCommand
import org.cubexmc.statecharge.config.LanguageManager
import org.cubexmc.statecharge.config.StateDefinitions
import org.cubexmc.statecharge.gui.StateShopGui
import org.cubexmc.statecharge.listener.StateListener
import org.cubexmc.statecharge.service.I18nStateNotifier
import org.cubexmc.statecharge.service.StateChargeService
import org.cubexmc.statecharge.storage.StateStorage
import org.cubexmc.statecharge.util.TimeFormat
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * 状态收费插件:玩家自主 toggle 开关状态(变小/变大/飞行/…),**按实际开启时长**从 Vault 扣费。
 * 关掉即停止计费;离线不计费;余额跌破保险阈值自动关闭全部收费状态。
 * 架构照抄 Contract(共享模块参考适配插件)。
 */
class StateChargePlugin : CubexPlugin() {
    private var cubexScheduler: CubexScheduler? = null
    private var resourceFiles: ResourceFiles? = null
    private var languageManager: LanguageManager? = null
    private var economyService: VaultEconomy? = null
    private var storage: StateStorage? = null
    private var definitions: StateDefinitions? = null
    private var notifier: I18nStateNotifier? = null
    private var service: StateChargeService? = null
    private var shopGui: StateShopGui? = null
    private var tickTask: CubexTask? = null
    private var flushTask: CubexTask? = null

    override fun enablePlugin() {
        cubexScheduler = CubexScheduler.bindTo(this)
        resourceFiles = ResourceFiles(this)
        saveDefaultFiles()
        try {
            migrateConfigAndLang()
        } catch (ex: MigrationException) {
            log().severe("StateCharge enable aborted: migration failed. ${ex.message}")
            abortEnable("StateCharge migration failed. See logs for details.")
        }
        reloadConfig()

        languageManager = LanguageManager(this)
        lang().load()

        economyService = VaultEconomy.hook(this, log())
        if (economyService == null) {
            abortEnable("Vault economy provider not found. StateCharge will be disabled.")
        }
        log().info("Vault economy hooked: ${economy().provider()}")
        applyEconomyAccount()

        // Store 是 Terminable,bind() 托管关停 flush;无需手写 flush lambda(Contract 模式)。
        storage = bind(StateStorage(this))
        storage().load()

        definitions = StateDefinitions(this)
        definitions().reload()

        notifier = I18nStateNotifier(this)
        service = StateChargeService(this)

        // 交易页:菜单路由与聊天输入各是一个 Listener,都要注册。
        shopGui = StateShopGui(this)
        registerListener(shop().registry())
        registerListener(shop())

        registerListener(StateListener(this))
        registerCommand("statecharge", StateChargeCommand(this))

        scheduleTick()
        scheduleFlush()

        // 插件 reload 期间在线玩家可能处于无效果状态:启动时统一补上。
        for (player in Bukkit.getOnlinePlayers()) {
            scheduleAtPlayer(player) { states().applyAll(it) }
        }

        log().info(
            "StateCharge enabled with ${definitions().all().size} configured states " +
                "(${definitions().purchasable().size} enabled), ${storage().size()} active states, " +
                "billing every ${billingIntervalSeconds()}s.",
        )
    }

    override fun disablePlugin() {
        suspendOnlineEffectsForDisable()
    }

    /**
     * 按命名 [ReloadChain] 重载配置、语言与磁盘数据。
     *
     * 两条顺序规则由链表达而非手写标志:
     * - store 重载**受 flush 成功门控**:flush 失败时磁盘是旧数据,重载会丢掉内存里的扣减;
     * - 迁移失败用 [ReloadFailurePolicy.ABORT],半迁移状态上继续重载更糟。
     *
     * 返回报告,命令层据此告诉服主哪一段炸了。
     */
    fun reloadStates(): ReloadReport {
        val report = ReloadChain.create()
            .failurePolicy(ReloadFailurePolicy.ABORT)
            .add("flush-store", Reloadable { storage().flushIfDirty() })
            .add("suspend-effects", Reloadable {
                for (player in Bukkit.getOnlinePlayers()) scheduleAtPlayer(player) { states().suspendAll(it) }
            })
            .add("default-files", Reloadable { saveDefaultFiles() })
            .add("migrations", Reloadable { migrateConfigAndLang() })
            .add("config", ConfigReload.bukkitConfig(this))
            .add("economy-account", Reloadable { applyEconomyAccount() })
            .add("language", lang())
            .add("definitions", Reloadable {
                definitions().reload()
                states().rememberDefinitions()
            })
            .add("store", storage())
            .add("timers", Reloadable {
                scheduleTick()
                scheduleFlush()
            })
            .run()

        // Even a later reload stage can fail after effects were suspended. Always restore from the
        // last in-memory state so a failed admin reload does not leave online players half-disabled.
        for (player in Bukkit.getOnlinePlayers()) {
            scheduleAtPlayer(player) { states().applyAll(it) }
        }

        for (summary in report.failureSummaries()) {
            log().severe("StateCharge reload stage failed — $summary")
        }
        if (report.skipped().isNotEmpty()) {
            log().warn("StateCharge reload skipped stages: ${report.skipped().joinToString(", ")}")
        }
        return report
    }

    /** 在玩家所在区域执行效果操作(Folia 安全)。 */
    fun scheduleAtPlayer(player: Player, block: (Player) -> Unit) {
        scheduler().runAtEntity(player, Runnable { block(player) })
    }

    /** 剩余秒数的人类可读文本(语言化单位)。 */
    fun durationText(seconds: Long): String = TimeFormat.format(
        seconds,
        mapOf(
            "day" to lang().ui("time-day"),
            "hour" to lang().ui("time-hour"),
            "minute" to lang().ui("time-minute"),
            "second" to lang().ui("time-second"),
        ),
    )

    fun lang(): LanguageManager = languageManager ?: throw IllegalStateException("languageManager not initialized")

    fun economy(): VaultEconomy = economyService ?: throw IllegalStateException("economyService not initialized")

    fun storage(): StateStorage = storage ?: throw IllegalStateException("storage not initialized")

    fun definitions(): StateDefinitions = definitions ?: throw IllegalStateException("definitions not initialized")

    fun notifier(): I18nStateNotifier = notifier ?: throw IllegalStateException("notifier not initialized")

    fun states(): StateChargeService = service ?: throw IllegalStateException("service not initialized")

    fun shop(): StateShopGui = shopGui ?: throw IllegalStateException("shopGui not initialized")

    fun scheduler(): CubexScheduler = cubexScheduler ?: throw IllegalStateException("scheduler not initialized")

    /**
     * 结算周期(秒)。默认 60 —— Vault 调用频率可控,玩家对扣费的感知也够及时。
     * 调小会让扣费更细,但每个开着状态的在线玩家每周期都要走 Vault:
     * 一次扣款,外加一次入账(`economy.account` 非空时)。
     */
    fun billingIntervalSeconds(): Long = max(1L, config.getLong("billing.interval-seconds", 60L))

    /**
     * 余额保险的默认阈值:结算后余额低于它就自动关掉全部收费状态。
     * 0 = 默认不设保险;玩家可用 `/sc guard <金额>` 或 GUI 自行设置。
     */
    fun defaultGuard(): BigDecimal =
        BigDecimal.valueOf(max(0.0, config.getDouble("billing.default-guard", 0.0)))

    /**
     * 把 `economy.account` 解析成入账目标 —— 玩家付的钱转到哪个账户。
     *
     * enable 与 reload 各解析一次:按名字找账户要查 usercache / 存档,
     * 这个代价不能落进每分钟一次的结算里(解析结果由 [VaultEconomy] 持有)。
     *
     * 配置写错**不阻止插件启动**:状态照常开关、照常扣钱,只是钱不入账。
     * 这是有意的取舍 —— 因为一行配置写错就让整个插件下线,对服主更糟;
     * 代价由 [VaultEconomy] 那边的 SEVERE + 每次扣款一条 WARNING 兜住。
     */
    private fun applyEconomyAccount() {
        val account = try {
            EconomyAccount.parse(config.getString("economy.account", ""))
        } catch (ex: IllegalArgumentException) {
            log().severe("StateCharge economy.account is invalid; charges will not be banked. ${ex.message}")
            EconomyAccount.None
        }
        economy().useAccount(account)
    }

    private fun saveDefaultFiles() {
        resourceFiles?.saveIfMissing(listOf("config.yml", "lang/zh_CN.yml", "lang/en_US.yml"))
    }

    @Throws(MigrationException::class)
    private fun migrateConfigAndLang() {
        val migrations = MigrationRunner(this)
        migrations.run(
            // v1 起步:plan 只用来确立 config-version 键;后续 schema 变化从这里加步骤。
            MigrationPlan.yaml("StateCharge config", "config.yml")
                .versionKey("config-version")
                .missingVersion(1)
                .targetVersion(2)
                .addStep(economyAccountStep()),
        )
    }

    /** v1 -> v2: 加入 `economy.account`,让收上来的钱能转进服务器账户而不是凭空消失。 */
    private fun economyAccountStep(): MigrationStep = object : MigrationStep {
        override fun fromVersion(): Int = 1

        override fun toVersion(): Int = 2

        override fun description(): String = "Add economy.account (where charged money is routed)."

        override fun migrate(context: MigrationContext) {
            if (!context.yaml().contains("economy.account")) {
                // 默认空串 = 保持 v1 的行为(销毁)。不替服主猜一个账户往里转钱。
                context.yaml()["economy.account"] = ""
            }
        }
    }

    private fun scheduleTick() {
        tickTask?.cancel()
        val tickSeconds = max(1L, config.getLong("timing.tick-seconds", 1L))
        tickTask = scheduler().runGlobalTimer(Runnable {
            try {
                states().tick(tickSeconds)
            } catch (ex: Exception) {
                log().warn("StateCharge tick failed: ${ex.message}")
            }
        }, tickSeconds * 20L, tickSeconds * 20L)
    }

    private fun scheduleFlush() {
        flushTask?.cancel()
        val intervalSeconds = max(5L, config.getLong("storage.flush-interval-seconds", 60L))
        flushTask = scheduler().runGlobalTimer(Runnable {
            try {
                storage().flushIfDirty()
            } catch (ex: IOException) {
                log().warn("StateCharge flush failed: ${ex.message}")
            }
        }, intervalSeconds * 20L, intervalSeconds * 20L)
    }

    private fun suspendOnlineEffectsForDisable() {
        val players = Bukkit.getOnlinePlayers().toList()
        if (!scheduler().isFolia) {
            players.forEach(states()::suspendAll)
            return
        }
        val completed = CountDownLatch(players.size)
        for (player in players) {
            scheduler().runAtEntity(player, Runnable {
                try {
                    states().suspendAll(player)
                } finally {
                    completed.countDown()
                }
            })
        }
        if (!completed.await(DISABLE_EFFECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            log().warn(
                "Timed out waiting for ${completed.count} Folia player effect cleanup task(s); " +
                    "their persisted leases will be recovered on the next enable.",
            )
        }
    }

    private companion object {
        const val DISABLE_EFFECT_TIMEOUT_SECONDS = 5L
    }
}
