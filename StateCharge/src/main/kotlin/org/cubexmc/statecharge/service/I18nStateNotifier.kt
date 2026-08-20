package org.cubexmc.statecharge.service

import java.math.BigDecimal
import org.bukkit.entity.Player
import org.cubexmc.statecharge.StateChargePlugin

/**
 * 用语言文件渲染计费提醒。
 *
 * 周期扣款默认**不提示**（`notifications.charge-message: false`）——按分钟结算的话，
 * 每次都刷一条聊天会把玩家淹掉。扣款失败与余额保险则一定提示：那两件事会让状态突然消失，
 * 玩家必须知道为什么。
 */
class I18nStateNotifier(private val plugin: StateChargePlugin) : StateNotifier {

    override fun charged(player: Player, stateId: String, seconds: Long, amount: BigDecimal) {
        if (!plugin.config.getBoolean("notifications.charge-message", false)) {
            return
        }
        player.sendMessage(
            plugin.lang().message(
                "charged",
                mapOf(
                    "state" to displayOf(stateId),
                    "time" to plugin.durationText(seconds),
                    "price" to plugin.economy().format(amount),
                ),
            ),
        )
    }

    override fun chargeFailed(player: Player, stateId: String) {
        player.sendMessage(
            plugin.lang().message("charge-failed", mapOf("state" to displayOf(stateId))),
        )
    }

    override fun guardTriggered(player: Player, turnedOff: List<String>) {
        player.sendMessage(
            plugin.lang().message(
                "guard-triggered",
                mapOf(
                    "states" to turnedOff.joinToString(", "),
                    "threshold" to plugin.economy().format(plugin.states().guardOf(player.uniqueId)),
                ),
            ),
        )
    }

    private fun displayOf(stateId: String): String =
        plugin.definitions().byId(stateId)?.display() ?: stateId
}
