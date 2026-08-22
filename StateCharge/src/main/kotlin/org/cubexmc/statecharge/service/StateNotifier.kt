package org.cubexmc.statecharge.service

import java.math.BigDecimal
import org.bukkit.entity.Player

/**
 * 计费过程中**玩家没有主动发起**的那几件事的挂点。
 *
 * toggle 开/关的即时反馈由命令层与 GUI 自己发——那是玩家点出来的，不该绕一层。
 * 这里只管"自己冒出来"的三件：周期扣款、扣款失败、余额保险触发。
 */
interface StateNotifier {

    /** 一次结算成功。[seconds] 是本次结算覆盖的开启时长。 */
    fun charged(player: Player, stateId: String, seconds: Long, amount: BigDecimal)

    /** 扣款失败，该状态已被强制关闭。 */
    fun chargeFailed(player: Player, stateId: String)

    /** 余额跌破保险阈值，[turnedOff] 里的状态已被自动关闭。 */
    fun guardTriggered(player: Player, turnedOff: List<String>)
}
