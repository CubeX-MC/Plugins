package org.cubexmc.statecharge.effect

import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.cubexmc.core.PlayerStateLeaseStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 飞行状态效果。
 *
 * - [start] 授予飞行能力,配置了 auto-start 时同时起飞;
 * - [reapply] 只补飞行能力,不强迫玩家保持飞行(玩家手动落地后不会被拽回空中);
 * - [remove] 到期清理:创造/旁观或持有 `statecharge.fly.keep` 权限的玩家保留飞行。
 */
class FlightEffect(stateId: String, private val autoStart: Boolean) : StateEffect {
    private val token = "statecharge:$stateId"
    private val legacySnapshots = ConcurrentHashMap<UUID, Boolean>()

    override fun start(player: Player) {
        apply(player)
        if (autoStart) {
            player.isFlying = true
        }
    }

    override fun reapply(player: Player) {
        val coordinated = PlayerStateLeaseStack.reapply(player, CHANNEL, token) { player.allowFlight = it.toBoolean() }
        if (!coordinated) apply(player)
    }

    override fun remove(player: Player) {
        val keep = player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR ||
            player.hasPermission(KEEP_PERMISSION)
        val coordinated = PlayerStateLeaseStack.remove(player, CHANNEL, token) { value ->
            player.allowFlight = value.toBoolean()
            if (!player.allowFlight) player.isFlying = false
        }
        if (!coordinated) {
            val previous = legacySnapshots.remove(player.uniqueId) ?: false
            player.allowFlight = previous || keep
            if (!player.allowFlight) player.isFlying = false
        } else if (keep && !PlayerStateLeaseStack.hasLeases(player, CHANNEL)) {
            player.allowFlight = true
        }
        legacySnapshots.remove(player.uniqueId)
    }

    private fun apply(player: Player) {
        legacySnapshots.putIfAbsent(player.uniqueId, player.allowFlight)
        if (!PlayerStateLeaseStack.apply(
                player,
                CHANNEL,
                token,
                true.toString(),
                { player.allowFlight.toString() },
                { player.allowFlight = it.toBoolean() },
            )
        ) {
            player.allowFlight = true
        }
    }

    private companion object {
        const val KEEP_PERMISSION = "statecharge.fly.keep"
        const val CHANNEL = "allow-flight"
    }
}
