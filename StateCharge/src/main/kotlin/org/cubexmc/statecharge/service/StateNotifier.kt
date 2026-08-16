package org.cubexmc.statecharge.service

import org.bukkit.entity.Player

/**
 * 计时提醒的挂点:服务层只报告剩余秒数变化,由实现决定提醒策略(阈值/actionbar 等)。
 */
interface StateNotifier {
    /** 剩余时长变化时回调(每秒一次)。 */
    fun onTick(player: Player, stateId: String, remainingSeconds: Long)

    /** 状态到期。 */
    fun expired(player: Player, stateId: String)
}
