package org.cubexmc.economy

import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.Plugin
import org.cubexmc.core.CubexLogger
import java.math.BigDecimal

/**
 * Vault 经济封装,外加一条"钱转到哪里去"的路由。
 *
 * CubeX 的经济是内循环的:插件从玩家身上收走的钱不该凭空消失,而要转进服务器的
 * 银行账户(见 [EconomyAccount])。各插件在接入本模块之前都是直接 `withdrawPlayer`,
 * 货币总量单向下降。
 *
 * ### 状态与线程
 *
 * 本类持有的唯一状态是解析好的入账目标 —— 它在 enable 与 reload 时由 [useAccount]
 * 整体替换,替换代价是一次性的名字解析,**绝不在每次扣款时重做**。
 * 所有方法都应在主线程(或 Folia 的对应线程)调用,和 Vault 本身的要求一致。
 *
 * ### 不做的事
 *
 * 不做跨账户事务。Vault 没有事务语义,`withdraw` 与 `deposit` 是两次独立操作;
 * 本类对这一点的取舍写在 [charge] 上。
 */
class VaultEconomy @JvmOverloads constructor(
    private val economy: Economy,
    private val logger: CubexLogger,
    private val lookup: OfflinePlayerLookup = BukkitOfflinePlayerLookup,
) {

    private var target: Target = Target.None
    private var resolvedSpec: EconomyAccount? = null

    /** 底层经济插件的名字,用于启动日志。 */
    fun provider(): String = economy.name

    /**
     * 设置(或重设)入账目标。enable 时调一次,reload 时再调一次。
     *
     * **配置没变且上次解析成功时直接跳过**:按名字解析在在线模式下会触发一次
     * 对 profile 服务的阻塞查询,不该每次 `/reload` 都来一遍。
     * 上次解析失败(目标是坏的)则一定重试 —— 服主修好配置或等 profile 服务恢复后,
     * 一次 reload 就能救回来,不必重启服务器。
     *
     * 解析失败**不抛异常**:记日志、把目标标成坏的,让插件照常跑。
     * 之后每次扣款都会再警告一次 —— 配置错导致的资金流失必须是吵闹的。
     */
    fun useAccount(spec: EconomyAccount) {
        if (spec == resolvedSpec && target !is Target.Broken) return
        resolvedSpec = spec
        target = resolve(spec)
    }

    /** 当前入账目标的人类可读描述。 */
    fun accountDescription(): String = target.description

    /** 入账目标是否处于"配置有效且解析成功"的状态。[EconomyAccount.None] 也算有效。 */
    fun accountUsable(): Boolean = target !is Target.Broken

    fun has(player: OfflinePlayer, amount: BigDecimal): Boolean {
        if (amount.signum() < 0) return false
        if (amount.signum() == 0) return true
        return economy.has(player, amount.toDouble())
    }

    fun balance(player: OfflinePlayer): BigDecimal = BigDecimal.valueOf(economy.getBalance(player))

    /** 只扣款,不入账。需要走内循环的调用方应当用 [charge]。 */
    fun withdraw(player: OfflinePlayer, amount: BigDecimal): EconomyResult {
        if (amount.signum() < 0) return EconomyResult.fail("amount must not be negative")
        if (amount.signum() == 0) return EconomyResult.ok()
        return toResult(economy.withdrawPlayer(player, amount.toDouble()))
    }

    /** 只入账给指定玩家(退款、赔付)。入账到 `economy.account` 用 [charge]。 */
    fun deposit(player: OfflinePlayer, amount: BigDecimal): EconomyResult {
        if (amount.signum() < 0) return EconomyResult.fail("amount must not be negative")
        if (amount.signum() == 0) return EconomyResult.ok()
        return toResult(economy.depositPlayer(player, amount.toDouble()))
    }

    /**
     * 从 [player] 扣 [amount],并转进配置好的 `economy.account`。
     *
     * 返回值的 `success()` 表达的是**扣款**成不成 —— 调用方据此决定玩家能不能用这次服务。
     *
     * **扣款成功后一律不回滚。** 入账失败时这笔钱确实消失了,但把它退回玩家更糟:
     * 玩家已经消费掉了服务(飞过了、变大过了、坐过车了),退款等于白送;
     * 而且退款本身同样可能被经济插件拒绝,那时账目更难对。
     * 因此入账失败只记 WARNING 并在结果里挂上 [EconomyResult.depositFailed],
     * 交给服主对账 —— 这是唯一一处会让货币总量下降的路径,必须留痕。
     */
    fun charge(player: OfflinePlayer, amount: BigDecimal): EconomyResult {
        if (amount.signum() < 0) return EconomyResult.fail("amount must not be negative")
        if (amount.signum() == 0) return EconomyResult.ok()

        val withdrawal = withdraw(player, amount)
        if (!withdrawal.success()) return withdrawal

        val routed = depositToAccount(amount)
        if (routed.success()) return EconomyResult.ok()

        logger.warn(
            "Charged ${format(amount)} from ${player.name ?: player.uniqueId} but could not route it to " +
                "${target.description}: ${routed.reason()}. " +
                "The money is gone - reconcile manually and fix economy.account.",
        )
        return EconomyResult.okButNotBanked(routed.reason())
    }

    /**
     * 交给经济插件格式化金额。
     *
     * 兜底不是多余的:Vault 的 `format` 是第三方实现,返回 null 会把一条**日志或提示**
     * 变成一次 NPE —— 而它最常出现的地方恰好是入账失败的警告里。
     */
    fun format(amount: BigDecimal): String =
        economy.format(amount.toDouble()) ?: amount.toPlainString()

    @Suppress("DEPRECATION") // NamedAccount 走的就是 Vault 的 name 重载,这是它的全部意义。
    private fun depositToAccount(amount: BigDecimal): EconomyResult = when (val current = target) {
        is Target.None -> EconomyResult.ok()
        is Target.PlayerAccount -> toResult(economy.depositPlayer(current.player, amount.toDouble()))
        is Target.NamedAccount -> toResult(economy.depositPlayer(current.name, amount.toDouble()))
        is Target.BankAccount -> toResult(economy.bankDeposit(current.name, amount.toDouble()))
        is Target.Broken -> EconomyResult.fail(current.detail)
    }

    private fun resolve(spec: EconomyAccount): Target = when (spec) {
        is EconomyAccount.None -> {
            logger.info("Economy account is not configured; charged money will be destroyed.")
            Target.None
        }

        is EconomyAccount.PlayerUuid -> playerTarget(lookup.byUuid(spec.uuid), spec.uuid.toString(), "config")

        is EconomyAccount.RawName -> namedTarget(spec.name)

        is EconomyAccount.PlayerName -> when (val found = lookup.byName(spec.name)) {
            is NameLookup.Found -> playerTarget(found.player, spec.name, found.source)

            is NameLookup.Fabricated -> broken(
                spec,
                "the profile lookup for '${spec.name}' failed, so the server could only make up an " +
                    "offline-mode UUID for it - that is a different account. Either use uuid:<uuid>, " +
                    "or use name:${spec.name} to let ${economy.name} resolve the account itself",
            )

            is NameLookup.Unknown -> broken(
                spec,
                "no account named '${spec.name}' could be resolved; use uuid:<uuid>, " +
                    "or name:${spec.name} to let ${economy.name} resolve the account itself",
            )
        }

        is EconomyAccount.Bank -> if (economy.hasBankSupport()) {
            bankTarget(spec.name)
        } else {
            broken(spec, "${economy.name} does not support Vault bank accounts")
        }
    }

    private fun playerTarget(player: OfflinePlayer, configured: String, source: String): Target {
        val uuid = player.uniqueId
        val description = "player account '${player.name ?: configured}' (uuid $uuid)"
        logger.info("Economy account resolved via $source: $description; charged money is routed there.")

        // 名字解析成功一次就够了:把 UUID 抄进配置,以后既不依赖 profile 缓存,
        // 也不受改名影响,更不会在 profile 服务挂掉时解析不出来。
        if (source != "config") {
            logger.info("Pin it with `economy.account: uuid:$uuid` so it no longer depends on the profile cache.")
        }
        if (!economy.hasAccount(player)) {
            logger.warn(
                "Economy account '$configured' ($uuid) does not exist in ${economy.name} yet. " +
                    "Most economy plugins create it on the first deposit; if the balance never moves, " +
                    "the configured account is wrong.",
            )
        }
        return Target.PlayerAccount(player, description)
    }

    /**
     * `name:<名字>` 的目标:名字原样交给 Vault 的 name 重载。
     *
     * 这条路径**不解析 UUID** —— 由经济插件用它自己的 name↔账户映射去认。
     * 对从不登录的虚拟银行账户来说这是最短的路,代价是我们无从核对钱进了哪个账户,
     * 因此启动时至少确认经济插件认得这个名字。
     */
    @Suppress("DEPRECATION")
    private fun namedTarget(name: String): Target {
        if (economy.hasAccount(name)) {
            logger.info(
                "Economy account resolved: ${economy.name} account '$name' " +
                    "(resolved by the economy plugin, not by UUID); charged money is routed there.",
            )
        } else {
            logger.warn(
                "${economy.name} does not know an account named '$name' yet. " +
                    "Create it first (most economy plugins have a command like `/eco set $name 0`), " +
                    "or the charges may be lost.",
            )
        }
        return Target.NamedAccount(name)
    }

    private fun bankTarget(name: String): Target {
        val balance = economy.bankBalance(name)
        if (balance.transactionSuccess()) {
            logger.info("Economy account resolved: Vault bank '$name'; charged money is routed there.")
        } else {
            logger.warn(
                "Vault bank '$name' is not readable (${reasonOf(balance)}). " +
                    "Create the bank in ${economy.name} first, or the charges will be lost.",
            )
        }
        return Target.BankAccount(name)
    }

    private fun broken(spec: EconomyAccount, detail: String): Target {
        logger.severe(
            "economy.account is set to '${spec.label()}' but it cannot be used: $detail. " +
                "Charges will still be taken from players and will be LOST until this is fixed.",
        )
        return Target.Broken(spec, detail)
    }

    /**
     * Vault 的返回值收成 [EconomyResult]。
     *
     * 接受 null 不是防御性编程过头:`Economy` 是第三方实现,返回 null 会让扣款路径
     * 直接抛 NPE —— 而调用方(比如按周期结算的计时器)那时已经把累计清掉了,
     * 结果就是"这一笔既没扣到钱也没留下痕迹"。当成失败处理才有日志可查。
     */
    private fun toResult(response: EconomyResponse?): EconomyResult = when {
        response == null -> EconomyResult.fail("${economy.name} returned no response")
        response.transactionSuccess() -> EconomyResult.ok()
        else -> EconomyResult.fail(reasonOf(response))
    }

    private fun reasonOf(response: EconomyResponse): String =
        response.errorMessage?.takeUnless(String::isBlank) ?: "Vault reported ${response.type}"

    /** 解析好的入账目标。和 [EconomyAccount] 分开:那个是配置的形状,这个是运行时的形状。 */
    private sealed class Target(val description: String) {
        data object None : Target("none (money is destroyed)")

        class PlayerAccount(val player: OfflinePlayer, description: String) : Target(description)

        class NamedAccount(val name: String) : Target("economy account '$name' (by name)")

        class BankAccount(val name: String) : Target("Vault bank '$name'")

        class Broken(val spec: EconomyAccount, val detail: String) :
            Target("misconfigured (${spec.label()}: $detail)")
    }

    companion object {
        /** Vault 或经济插件缺席时返回 null;调用方据此决定是降级还是 abortEnable。 */
        @JvmStatic
        fun hook(plugin: Plugin, logger: CubexLogger): VaultEconomy? {
            if (plugin.server.pluginManager.getPlugin("Vault") == null) return null
            val registration = plugin.server.servicesManager.getRegistration(Economy::class.java) ?: return null
            return VaultEconomy(registration.provider, logger)
        }
    }
}
