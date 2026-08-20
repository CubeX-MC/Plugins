package org.cubexmc.statecharge.model

import java.math.BigDecimal
import java.math.MathContext
import org.bukkit.Material
import org.cubexmc.statecharge.effect.StateEffect

/**
 * 一个可开启的状态定义（`config.yml` 的 `states.<id>` 段解析结果）。
 *
 * 计费按**实际开启时长**走：[price] 与 [unitSeconds] 合起来是一个**费率**——每 [unitSeconds]
 * 秒收 [price]。玩家不预购时长，toggle 开启即开始计费、关闭即停止。
 */
class StateSpec(
    private val id: String,
    private val display: String,
    private val price: BigDecimal,
    private val unitSeconds: Long,
    private val permission: String?,
    private val conflictGroup: String?,
    private val effect: StateEffect,
    private val enabled: Boolean,
    private val icon: Material,
) {
    fun id(): String = id

    fun display(): String = display

    /** 每 [unitSeconds] 秒的价格。 */
    fun price(): BigDecimal = price

    /** 计费周期长度（秒）；与 [price] 合起来构成费率。 */
    fun unitSeconds(): Long = unitSeconds

    /** 开启该状态需要的权限节点，null = 不检查。 */
    fun permission(): String? = permission

    /** 互斥组，同组状态同时只能开启一个；null = 不互斥。 */
    fun conflictGroup(): String? = conflictGroup

    fun effect(): StateEffect = effect

    fun enabled(): Boolean = enabled

    /** 交易页里这个状态用的图标。 */
    fun icon(): Material = icon

    /** 免费状态不产生扣款，也不受余额保险影响。 */
    fun isFree(): Boolean = price.signum() <= 0

    /**
     * [seconds] 秒开启时长的费用。
     *
     * **不取整**：取整会在短周期结算下累积出可观偏差——每次抹掉不足一分的零头，
     * 玩家开一小时可能只被收几分钟的钱。Vault 本身就是 double，接得住小数。
     */
    fun costFor(seconds: Long): BigDecimal {
        if (seconds <= 0L || isFree() || unitSeconds <= 0L) {
            return BigDecimal.ZERO
        }
        return price.multiply(BigDecimal.valueOf(seconds))
            .divide(BigDecimal.valueOf(unitSeconds), MathContext.DECIMAL64)
    }
}
