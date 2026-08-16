package org.cubexmc.clarity

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/** 玩家进服时按配置黑名单自动清扫(默认关闭;延迟执行给其它插件留时间)。 */
class JoinListener(
    private val plugin: ClarityPlugin,
    private val service: ClarityService,
) : Listener {
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val config = plugin.config()
        if (!config.autoCleanOnJoin()) return
        service.sweep(null, event.player, config.joinDelayTicks())
    }
}
