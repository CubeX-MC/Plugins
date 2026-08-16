package org.cubexmc.statecharge.service

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.entity.Player
import org.cubexmc.statecharge.StateChargePlugin
import kotlin.math.max

/**
 * 用语言文件渲染提醒:阈值聊天提示(`notifications.expiry-warning-seconds`)+
 * 最后 N 秒 actionbar 倒计时(`notifications.actionbar-countdown-seconds`,0 关闭)。
 */
class I18nStateNotifier(private val plugin: StateChargePlugin) : StateNotifier {

    private val legacySerializer = LegacyComponentSerializer.legacySection()

    override fun onTick(player: Player, stateId: String, remainingSeconds: Long) {
        val display = displayOf(stateId)
        if (remainingSeconds in warningSeconds()) {
            player.sendMessage(
                plugin.lang().message(
                    "expiry-warning",
                    mapOf("state" to display, "time" to plugin.durationText(remainingSeconds)),
                ),
            )
        }
        val window = actionbarWindowSeconds()
        if (window > 0 && remainingSeconds <= window) {
            // i18n 输出 legacy §,actionbar 走 Adventure(Player#sendActionBar(String) 已弃用)。
            player.sendActionBar(
                legacySerializer.deserialize(
                    plugin.lang().message(
                        "actionbar-countdown",
                        mapOf("state" to display, "time" to plugin.durationText(remainingSeconds)),
                    ),
                ),
            )
        }
    }

    override fun expired(player: Player, stateId: String) {
        player.sendMessage(plugin.lang().message("expired", mapOf("state" to displayOf(stateId))))
    }

    private fun displayOf(stateId: String): String =
        plugin.definitions().byId(stateId)?.display() ?: stateId

    private fun warningSeconds(): Set<Long> =
        plugin.config.getLongList("notifications.expiry-warning-seconds")
            .mapTo(HashSet()) { max(0L, it) }

    private fun actionbarWindowSeconds(): Long =
        max(0L, plugin.config.getLong("notifications.actionbar-countdown-seconds", 10L))
}
