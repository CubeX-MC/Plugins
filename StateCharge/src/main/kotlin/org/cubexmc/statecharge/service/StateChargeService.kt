package org.cubexmc.statecharge.service

import java.math.BigDecimal
import java.util.UUID
import kotlin.math.max
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.attribute.Attribute
import org.cubexmc.core.PlayerStateLeaseStack
import org.cubexmc.statecharge.StateChargePlugin
import org.cubexmc.statecharge.model.StateSpec
import org.cubexmc.statecharge.effect.StateEffect
import java.util.concurrent.ConcurrentHashMap

/**
 * 状态的开关与**按实际开启时长**计费。
 *
 * 计费模型（2026-08-20 由"预购时长"改成这个）：
 * - 玩家 **toggle 开启** → 开始累计秒数；**toggle 关闭** → 立刻结算零头并停止累计。
 *   所以"关掉就不再计费"是字面成立的，玩家只为真正开着的时间付钱。
 * - [StateSpec.price] / [StateSpec.unitSeconds] 是**费率**，不足一个周期按比例收、不取整。
 * - **离线不计费**：[tick] 只遍历在线玩家。开关状态保留，重新上线接着算。
 * - **余额保险**：每次结算后余额低于阈值就自动关掉全部收费状态，防止玩家忘关被扣干。
 *
 * 免费状态（`price <= 0`）不参与结算，也不受余额保险影响。
 *
 * 线程模型（Folia 安全）：实体效果的施加/移除一律经 `scheduleAtPlayer` 落到玩家所在区域；
 * [tick] 从全局计时器调用，但每名玩家的累计、结算与 Vault 调用都投递到该玩家的实体线程。
 */
class StateChargeService(private val plugin: StateChargePlugin) {
    private val knownEffects: MutableMap<String, StateEffect> = ConcurrentHashMap()

    init {
        rememberDefinitions()
    }

    fun rememberDefinitions() {
        for (spec in plugin.definitions().all()) knownEffects[spec.id()] = spec.effect()
    }

    /** 玩家主动开关，或 GUI 点击。 */
    fun toggle(player: Player, stateId: String): ToggleResult {
        val spec = plugin.definitions().byId(stateId)
            ?: return ToggleResult.fail(ToggleResult.Failure.UNKNOWN_STATE, stateId)
        return if (plugin.storage().isActive(player.uniqueId, stateId)) {
            turnOff(player, spec)
        } else {
            turnOn(player, spec)
        }
    }

    fun turnOn(player: Player, spec: StateSpec): ToggleResult {
        if (!spec.enabled()) {
            return ToggleResult.fail(ToggleResult.Failure.DISABLED, spec.id())
        }
        val permission = spec.permission()
        if (permission != null && !player.hasPermission(permission)) {
            return ToggleResult.fail(ToggleResult.Failure.NO_PERMISSION, spec.id())
        }
        // 开启前先查保险:余额已经低于阈值时直接拒绝,而不是让它开一秒又被保险关掉。
        if (!spec.isFree() && balanceBelowGuard(player)) {
            return ToggleResult.fail(ToggleResult.Failure.GUARD_REACHED, spec.id())
        }

        // 互斥组内先关掉别的,再开这个。
        val group = spec.conflictGroup()
        if (group != null) {
            for (otherId in plugin.storage().activeStates(player.uniqueId)) {
                if (otherId == spec.id()) {
                    continue
                }
                val other = plugin.definitions().byId(otherId) ?: continue
                if (other.conflictGroup() == group) {
                    turnOff(player, other)
                }
            }
        }

        // 新的一次开启:累计总额从零开始算。
        plugin.storage().clearSessionCharged(player.uniqueId, spec.id())
        plugin.storage().setActive(player.uniqueId, spec.id(), true)
        knownEffects[spec.id()] = spec.effect()
        atPlayer(player) { spec.effect().start(it) }
        return ToggleResult.on(spec)
    }

    fun turnOff(player: Player, spec: StateSpec): ToggleResult {
        // 先结算零头:玩家已经用掉的这段时间照收,关闭只是停止继续累计。
        settleState(player, spec)
        // 报给玩家的是**本次开启以来的总额**,不是刚结算的那点零头。
        // 一个开了一小时的状态中间已经按周期扣过很多次,只报零头会让玩家以为整段就这么便宜。
        val sessionTotal = plugin.storage().sessionCharged(player.uniqueId, spec.id())
        plugin.storage().clearSessionCharged(player.uniqueId, spec.id())
        plugin.storage().setActive(player.uniqueId, spec.id(), false)
        atPlayer(player) { spec.effect().remove(it) }
        return ToggleResult.off(spec, sessionTotal)
    }

    /**
     * 计费主循环。[secondsElapsed] 是距上次 tick 的秒数。
     *
     * 只累计与结算**在线**玩家：离线时状态既不生效也不该收钱。
     */
    fun tick(secondsElapsed: Long) {
        if (!plugin.storage().anyActive()) {
            return
        }
        val step = max(1L, secondsElapsed)
        for (player in Bukkit.getOnlinePlayers()) {
            plugin.scheduleAtPlayer(player) { tickPlayer(it, step) }
        }
    }

    internal fun tickPlayer(player: Player, secondsElapsed: Long) {
        val activeStates = plugin.storage().activeStates(player.uniqueId)
        if (activeStates.isEmpty()) return
        val step = max(1L, secondsElapsed)
        val interval = plugin.billingIntervalSeconds()
        var due = false
        for (stateId in activeStates) {
            val spec = plugin.definitions().byId(stateId) ?: continue
            if (spec.isFree()) continue
            plugin.storage().addAccrued(player.uniqueId, stateId, step)
            if (plugin.storage().accruedSeconds(player.uniqueId, stateId) >= interval) due = true
        }
        if (due) settleAll(player)
    }

    /**
     * 结算该玩家全部未结算的时长，并在结算后检查余额保险。
     *
     * @return 实际扣掉的总金额
     */
    fun settleAll(player: Player): BigDecimal {
        var total = BigDecimal.ZERO
        for ((stateId, seconds) in plugin.storage().accruedFor(player.uniqueId)) {
            val spec = plugin.definitions().byId(stateId)
            if (spec == null || spec.isFree()) {
                plugin.storage().clearAccrued(player.uniqueId, stateId)
                continue
            }
            total = total.add(chargeFor(player, spec, seconds))
        }
        enforceGuard(player)
        return total
    }

    /** 结算单个状态的零头（toggle 关闭时用）。 */
    private fun settleState(player: Player, spec: StateSpec): BigDecimal {
        val seconds = plugin.storage().accruedSeconds(player.uniqueId, spec.id())
        if (seconds <= 0L || spec.isFree()) {
            plugin.storage().clearAccrued(player.uniqueId, spec.id())
            return BigDecimal.ZERO
        }
        return chargeFor(player, spec, seconds)
    }

    /**
     * 按 [seconds] 秒扣款并清掉累计。
     *
     * 扣款失败时**仍然清掉累计**并关掉该状态：留着它只会在下次结算时重复失败、反复刷日志，
     * 而这段时间玩家确实已经用了，收不回来。此处记 WARNING 供服主追查。
     *
     * 走 `charge()` 而不是 `withdraw()`：钱要转进 `economy.account`（内循环经济）。
     * 它的 `success()` 只表示**扣款**成不成 —— 入账到服务器账户失败时玩家照样付了钱、
     * 状态照常开着，只是那笔钱丢了，由 `VaultEconomy` 记 WARNING。
     */
    private fun chargeFor(player: Player, spec: StateSpec, seconds: Long): BigDecimal {
        val cost = spec.costFor(seconds)
        plugin.storage().clearAccrued(player.uniqueId, spec.id())
        if (cost.signum() <= 0) {
            return BigDecimal.ZERO
        }
        val result = runCatching { plugin.economy().charge(player, cost) }.getOrElse { error ->
            plugin.log().warn("Vault provider threw while charging ${player.name} for state ${spec.id()}: ${error.message}")
            forceOff(player, spec)
            plugin.notifier().chargeFailed(player, spec.id())
            return BigDecimal.ZERO
        }
        if (!result.success()) {
            plugin.log().warn(
                "Failed to charge ${player.name} ${plugin.economy().format(cost)} " +
                    "for state ${spec.id()}: ${result.reason()}",
            )
            forceOff(player, spec)
            plugin.notifier().chargeFailed(player, spec.id())
            return BigDecimal.ZERO
        }
        plugin.storage().addSessionCharged(player.uniqueId, spec.id(), cost)
        plugin.notifier().charged(player, spec.id(), seconds, cost)
        return cost
    }

    /** 余额跌破保险阈值就关掉全部收费状态。 */
    private fun enforceGuard(player: Player) {
        if (!balanceBelowGuard(player)) {
            return
        }
        val turnedOff = mutableListOf<String>()
        for (stateId in plugin.storage().activeStates(player.uniqueId)) {
            val spec = plugin.definitions().byId(stateId) ?: continue
            if (spec.isFree()) {
                continue
            }
            turnOff(player, spec)
            turnedOff.add(spec.display())
        }
        if (turnedOff.isNotEmpty()) {
            plugin.notifier().guardTriggered(player, turnedOff)
        }
    }

    /** 余额是否已经低于该玩家的保险阈值。阈值 <= 0 表示关闭保险。 */
    fun balanceBelowGuard(player: Player): Boolean {
        val threshold = guardOf(player.uniqueId)
        if (threshold.signum() <= 0) {
            return false
        }
        return !plugin.economy().has(player, threshold)
    }

    /** 玩家自设阈值，未设则用配置默认值。 */
    fun guardOf(player: UUID): BigDecimal =
        plugin.storage().guard(player) ?: plugin.defaultGuard()

    fun setGuard(player: UUID, threshold: BigDecimal?) {
        plugin.storage().setGuard(player, threshold)
    }

    // ---- 效果重放(必须在实体区域上下文调用,由事件/scheduler 入口保证) ----

    /** 重放玩家全部开启中状态的效果（join/respawn/换世界/换模式）。 */
    fun applyAll(player: Player) {
        for (stateId in plugin.storage().activeStates(player.uniqueId)) {
            val effect = plugin.definitions().byId(stateId)?.effect() ?: knownEffects[stateId]
            if (effect != null) {
                effect.reapply(player)
            } else {
                removeUnknownEffect(player, stateId)
                plugin.storage().setActive(player.uniqueId, stateId, false)
                plugin.storage().clearAccrued(player.uniqueId, stateId)
                plugin.storage().clearSessionCharged(player.uniqueId, stateId)
                plugin.log().warn("Removed unknown active state $stateId from ${player.name}; its definition no longer exists.")
            }
        }
    }

    /** Remove live effects without changing billing state; used around reload and plugin disable. */
    fun suspendAll(player: Player) {
        for (stateId in plugin.storage().activeStates(player.uniqueId)) knownEffects[stateId]?.remove(player)
    }

    /** 移除单个状态的效果（清除路径）。 */
    fun removeFor(player: Player, stateId: String) {
        (plugin.definitions().byId(stateId)?.effect() ?: knownEffects[stateId])?.remove(player)
    }

    // ---- 管理员 ----

    /** 强制关闭且不结算（扣款已失败的路径用，避免二次扣款尝试）。 */
    fun forceOff(player: Player, spec: StateSpec) {
        plugin.storage().clearAccrued(player.uniqueId, spec.id())
        plugin.storage().clearSessionCharged(player.uniqueId, spec.id())
        plugin.storage().setActive(player.uniqueId, spec.id(), false)
        atPlayer(player) { spec.effect().remove(it) }
    }

    /** 关掉某在线玩家的全部（或指定）状态，返回关掉的个数。 */
    fun clear(target: Player, stateId: String?): Int {
        var count = 0
        for (activeId in plugin.storage().activeStates(target.uniqueId)) {
            if (stateId != null && activeId != stateId) {
                continue
            }
            val spec = plugin.definitions().byId(activeId)
            if (spec == null) {
                knownEffects[activeId]?.remove(target) ?: removeUnknownEffect(target, activeId)
                plugin.storage().setActive(target.uniqueId, activeId, false)
                plugin.storage().clearAccrued(target.uniqueId, activeId)
                plugin.storage().clearSessionCharged(target.uniqueId, activeId)
            } else {
                turnOff(target, spec)
            }
            count++
        }
        return count
    }

    private fun atPlayer(player: Player, block: (Player) -> Unit) {
        plugin.scheduleAtPlayer(player, block)
    }

    private fun removeUnknownEffect(player: Player, stateId: String) {
        val token = "statecharge:$stateId"
        PlayerStateLeaseStack.remove(player, "scale", token) { value ->
            player.getAttribute(Attribute.SCALE)?.baseValue = value.toDouble()
        }
        PlayerStateLeaseStack.remove(player, "allow-flight", token) { value ->
            player.allowFlight = value.toBoolean()
            if (!player.allowFlight) player.isFlying = false
        }
    }
}

/** [StateChargeService.toggle] 的结果。 */
class ToggleResult private constructor(
    private val success: Boolean,
    private val nowActive: Boolean,
    private val spec: StateSpec?,
    private val stateId: String,
    private val charged: BigDecimal,
    private val failure: Failure?,
) {
    fun success(): Boolean = success

    /** 操作之后该状态是开还是关。 */
    fun nowActive(): Boolean = nowActive

    fun spec(): StateSpec? = spec

    fun stateId(): String = stateId

    /**
     * 本次开启到关闭**累计**扣掉的总金额（开启时为 0）。
     *
     * 不是关闭那一刻结算的零头 —— 按周期计费的状态在关闭前已经扣过很多次，
     * 只报最后一次会让玩家以为整段开启就花了那么点钱。
     */
    fun charged(): BigDecimal = charged

    fun failure(): Failure? = failure

    enum class Failure {
        UNKNOWN_STATE,
        DISABLED,
        NO_PERMISSION,

        /** 余额已经低于保险阈值，开了也会立刻被关掉。 */
        GUARD_REACHED,
    }

    companion object {
        @JvmStatic
        fun on(spec: StateSpec): ToggleResult =
            ToggleResult(true, true, spec, spec.id(), BigDecimal.ZERO, null)

        @JvmStatic
        fun off(spec: StateSpec, charged: BigDecimal): ToggleResult =
            ToggleResult(true, false, spec, spec.id(), charged, null)

        @JvmStatic
        fun fail(failure: Failure, stateId: String): ToggleResult =
            ToggleResult(false, false, null, stateId, BigDecimal.ZERO, failure)
    }
}
