package org.cubexmc.statecharge.effect

import org.bukkit.GameMode
import org.bukkit.entity.Player

/**
 * 飞行状态效果。
 *
 * - [start] 授予飞行能力,配置了 auto-start 时同时起飞;
 * - [reapply] 只补飞行能力,不强迫玩家保持飞行(玩家手动落地后不会被拽回空中);
 * - [remove] 到期清理:创造/旁观或持有 `statecharge.fly.keep` 权限的玩家保留飞行。
 */
class FlightEffect(private val autoStart: Boolean) : StateEffect {

    override fun start(player: Player) {
        player.allowFlight = true
        if (autoStart) {
            player.isFlying = true
        }
    }

    override fun reapply(player: Player) {
        player.allowFlight = true
    }

    override fun remove(player: Player) {
        if (player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) {
            return
        }
        if (player.hasPermission(KEEP_PERMISSION)) {
            return
        }
        player.isFlying = false
        player.allowFlight = false
    }

    private companion object {
        const val KEEP_PERMISSION = "statecharge.fly.keep"
    }
}
