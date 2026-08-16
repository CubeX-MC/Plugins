package org.cubexmc.statecharge.service

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.cubexmc.statecharge.StateChargePlugin
import org.cubexmc.statecharge.model.StateSpec
import java.math.BigDecimal
import kotlin.math.max

/**
 * 状态收费核心:购买/赠送/清除/计时。
 *
 * 线程模型(Folia 安全):
 * - 实体效果的全部施加/移除都经 `scheduler().runAtEntity` 落到玩家所在区域;
 * - [tick] 从全局计时器调用,只做存储扣减与消息提醒(不碰实体),到期移除另走 runAtEntity。
 */
class StateChargeService(private val plugin: StateChargePlugin) {

    // ---- 购买 ----

    fun buy(player: Player, stateId: String, count: Int): BuyResult {
        val spec = plugin.definitions().byId(stateId)
            ?: return BuyResult.fail(BuyResult.Failure.UNKNOWN_STATE)
        if (!spec.enabled()) {
            return BuyResult.fail(BuyResult.Failure.DISABLED)
        }
        val permission = spec.permission()
        if (permission != null && !player.hasPermission(permission)) {
            return BuyResult.fail(BuyResult.Failure.NO_PERMISSION)
        }
        if (count <= 0 || count > MAX_BUY_COUNT) {
            return BuyResult.fail(BuyResult.Failure.INVALID_COUNT)
        }
        val conflict = activeConflict(player, spec)
        if (conflict != null) {
            return BuyResult.fail(BuyResult.Failure.CONFLICT, conflictId = conflict.id())
        }
        val added = try {
            Math.multiplyExact(spec.unitSeconds(), count.toLong())
        } catch (ex: ArithmeticException) {
            return BuyResult.fail(BuyResult.Failure.MAX_STACK)
        }
        val current = plugin.storage().remaining(player.uniqueId, stateId)
        val newRemaining = try {
            Math.addExact(current, added)
        } catch (ex: ArithmeticException) {
            return BuyResult.fail(BuyResult.Failure.MAX_STACK)
        }
        val maxStack = spec.maxStackSeconds()
        if (maxStack > 0 && newRemaining > maxStack) {
            return BuyResult.fail(BuyResult.Failure.MAX_STACK)
        }
        val price = spec.price().multiply(BigDecimal.valueOf(count.toLong()))
        if (!plugin.economy().has(player, price)) {
            return BuyResult.fail(BuyResult.Failure.INSUFFICIENT_FUNDS, price = price)
        }
        val transaction = plugin.economy().withdraw(player, price)
        if (!transaction.success()) {
            return BuyResult.fail(BuyResult.Failure.ECONOMY_FAILED, reason = transaction.reason())
        }
        plugin.storage().setRemaining(player.uniqueId, stateId, newRemaining)
        if (current <= 0) {
            atPlayer(player) { spec.effect().start(it) }
        } else {
            atPlayer(player) { spec.effect().reapply(it) }
        }
        return BuyResult.success(spec, added, newRemaining, price)
    }

    // ---- 计时 ----

    /** 全局计时器每秒调用:扣减在线玩家剩余时长,处理到期与提醒。 */
    fun tick(secondsElapsed: Long) {
        val step = max(1L, secondsElapsed)
        for (player in Bukkit.getOnlinePlayers()) {
            for ((stateId, remaining) in plugin.storage().active(player.uniqueId)) {
                val newRemaining = remaining - step
                if (newRemaining <= 0) {
                    plugin.storage().removeState(player.uniqueId, stateId)
                    plugin.definitions().byId(stateId)?.let { spec ->
                        atPlayer(player) { spec.effect().remove(it) }
                    }
                    plugin.notifier().expired(player, stateId)
                } else {
                    plugin.storage().setRemaining(player.uniqueId, stateId, newRemaining)
                    plugin.notifier().onTick(player, stateId, newRemaining)
                }
            }
        }
    }

    // ---- 效果重放(必须在实体区域上下文调用,由事件/scheduler 入口保证) ----

    /** 重放玩家全部生效状态的效果(join/respawn/换世界/换模式)。 */
    fun applyAll(player: Player) {
        for ((stateId, _) in plugin.storage().active(player.uniqueId)) {
            plugin.definitions().byId(stateId)?.effect()?.reapply(player)
        }
    }

    /** 移除单个状态的效果(到期/清除路径)。 */
    fun removeFor(player: Player, stateId: String) {
        plugin.definitions().byId(stateId)?.effect()?.remove(player)
    }

    // ---- 管理员操作 ----

    /** 免费发放(admin give)。不受 max-stack 与 enabled 限制,叠加到已有剩余时长。 */
    fun give(target: OfflinePlayer, stateId: String, seconds: Long): GiveResult {
        val spec = plugin.definitions().byId(stateId)
            ?: return GiveResult.fail(GiveResult.Failure.UNKNOWN_STATE)
        if (seconds <= 0) {
            return GiveResult.fail(GiveResult.Failure.INVALID_SECONDS)
        }
        val hadActive = plugin.storage().remaining(target.uniqueId, stateId) > 0
        plugin.storage().addSeconds(target.uniqueId, stateId, seconds)
        val remaining = plugin.storage().remaining(target.uniqueId, stateId)
        val online = target.player
        if (online != null) {
            atPlayer(online) { player ->
                if (hadActive) {
                    spec.effect().reapply(player)
                } else {
                    spec.effect().start(player)
                }
            }
        }
        return GiveResult.success(spec, remaining)
    }

    /** 清除目标玩家全部状态,或指定单个状态。返回清除条数。 */
    fun clear(target: OfflinePlayer, stateId: String?): Int {
        val active = plugin.storage().active(target.uniqueId)
        if (active.isEmpty()) {
            return 0
        }
        val online = target.player
        val toClear = if (stateId == null) active.keys.toList() else active.keys.filter { it == stateId }
        for (id in toClear) {
            if (online != null) {
                atPlayer(online) { removeFor(it, id) }
            }
            plugin.storage().removeState(target.uniqueId, id)
        }
        return toClear.size
    }

    // ---- 内部 ----

    private fun activeConflict(player: Player, spec: StateSpec): StateSpec? {
        val group = spec.conflictGroup() ?: return null
        for ((stateId, _) in plugin.storage().active(player.uniqueId)) {
            if (stateId == spec.id()) {
                continue
            }
            val other = plugin.definitions().byId(stateId) ?: continue
            if (other.conflictGroup() == group) {
                return other
            }
        }
        return null
    }

    private fun atPlayer(player: Player, block: (Player) -> Unit) {
        plugin.scheduler().runAtEntity(player, Runnable { block(player) })
    }

    private companion object {
        const val MAX_BUY_COUNT = 1000
    }
}

/** 购买结果:成功携带规格/新增时长/总剩余/总价;失败携带原因码与上下文。 */
class BuyResult private constructor(
    private val success: Boolean,
    private val failure: Failure?,
    private val spec: StateSpec?,
    private val addedSeconds: Long,
    private val remainingSeconds: Long,
    private val price: BigDecimal,
    private val conflictStateId: String?,
    private val reason: String?,
) {
    enum class Failure { UNKNOWN_STATE, DISABLED, NO_PERMISSION, CONFLICT, MAX_STACK, INVALID_COUNT, INSUFFICIENT_FUNDS, ECONOMY_FAILED }

    fun success(): Boolean = success

    fun failure(): Failure? = failure

    fun spec(): StateSpec? = spec

    fun addedSeconds(): Long = addedSeconds

    fun remainingSeconds(): Long = remainingSeconds

    /** 总价(成功时为实付金额;余额不足时为所需金额;其余失败为 0)。 */
    fun price(): BigDecimal = price

    /** 互斥冲突时,玩家当前生效的同组状态 id。 */
    fun conflictStateId(): String? = conflictStateId

    /** Vault 交易失败原因。 */
    fun reason(): String? = reason

    companion object {
        @JvmStatic
        fun success(spec: StateSpec, addedSeconds: Long, remainingSeconds: Long, price: BigDecimal): BuyResult =
            BuyResult(true, null, spec, addedSeconds, remainingSeconds, price, null, null)

        @JvmStatic
        fun fail(
            failure: Failure,
            conflictId: String? = null,
            reason: String? = null,
            price: BigDecimal = BigDecimal.ZERO,
        ): BuyResult = BuyResult(false, failure, null, 0L, 0L, price, conflictId, reason)
    }
}

/** 免费发放结果。 */
class GiveResult private constructor(
    private val success: Boolean,
    private val failure: Failure?,
    private val spec: StateSpec?,
    private val remainingSeconds: Long,
) {
    enum class Failure { UNKNOWN_STATE, INVALID_SECONDS }

    fun success(): Boolean = success

    fun failure(): Failure? = failure

    fun spec(): StateSpec? = spec

    fun remainingSeconds(): Long = remainingSeconds

    companion object {
        @JvmStatic
        fun success(spec: StateSpec, remainingSeconds: Long): GiveResult =
            GiveResult(true, null, spec, remainingSeconds)

        @JvmStatic
        fun fail(failure: Failure): GiveResult = GiveResult(false, failure, null, 0L)
    }
}
