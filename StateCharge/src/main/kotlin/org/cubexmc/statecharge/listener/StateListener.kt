package org.cubexmc.statecharge.listener

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.cubexmc.statecharge.StateChargePlugin

/**
 * 在效果可能丢失的场景重放:进服/重生/换世界/换游戏模式。
 * 全部经 `runAtEntity` 落到玩家所在区域(Folia 安全);离线时效果自然暂停,无需 quit 处理。
 */
class StateListener(private val plugin: StateChargePlugin) : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = reapply(event.player)

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) = reapply(event.player)

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) = reapply(event.player)

    @EventHandler
    fun onGameModeChange(event: PlayerGameModeChangeEvent) = reapply(event.player)

    private fun reapply(player: Player) {
        plugin.scheduleAtPlayer(player) { plugin.states().applyAll(it) }
    }
}
