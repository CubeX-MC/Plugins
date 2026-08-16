package org.cubexmc.statecharge.model

import org.cubexmc.statecharge.effect.StateEffect
import java.math.BigDecimal

/**
 * 一个可购买的状态定义(config.yml 的 `states.<id>` 段解析结果)。
 */
class StateSpec(
    private val id: String,
    private val display: String,
    private val price: BigDecimal,
    private val unitSeconds: Long,
    private val maxStackSeconds: Long,
    private val permission: String?,
    private val conflictGroup: String?,
    private val effect: StateEffect,
    private val enabled: Boolean,
) {
    fun id(): String = id

    fun display(): String = display

    fun price(): BigDecimal = price

    /** 每份时长(秒)。 */
    fun unitSeconds(): Long = unitSeconds

    /** 累计上限(秒),0 = 不限。 */
    fun maxStackSeconds(): Long = maxStackSeconds

    /** 购买前需要检查的权限节点,null = 不检查。 */
    fun permission(): String? = permission

    /** 互斥组,同组状态同时只能有一个生效;null = 不互斥。 */
    fun conflictGroup(): String? = conflictGroup

    fun effect(): StateEffect = effect

    fun enabled(): Boolean = enabled
}
