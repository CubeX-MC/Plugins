package org.cubexmc.cookbook.dailyreward

import org.bukkit.event.player.PlayerJoinEvent
import org.cubexmc.core.Cooldown
import org.cubexmc.core.CubexPlugin
import org.cubexmc.core.onEvent

/**
 * Cookbook 03 —— **每日签到**。演示 `onEvent` 与 `Cooldown`。
 *
 * 看点：
 * 1. [onEvent] 注册完**自动**绑进资源栈，不会出现"reload 后监听器还活着"这类不报错的 bug。
 * 2. [Cooldown] 的时长是 supplier —— 服主改了 `config.yml` 再 reload，立刻生效，不用重启。
 * 3. 提示文案的拼装是**纯函数**（[DailyReward]），单测直接打它，不需要起服务器。
 */
class DailyRewardPlugin : CubexPlugin() {

    private lateinit var claim: Cooldown

    override fun enablePlugin() {
        saveResourcesIfMissing("config.yml")
        claim = Cooldown({ config.getLong("cooldown-hours", 24L) * 3_600_000L })

        onEvent<PlayerJoinEvent> { event ->
            val player = event.player
            if (claim.tryUse(player.uniqueId)) {
                messager().send(player, text().color(config.getString("reward-message").orEmpty()))
                return@onEvent
            }
            val wait = DailyReward.waitText(claim.remainingSeconds(player.uniqueId))
            val template = config.getString("wait-message").orEmpty().replace("{time}", wait)
            messager().send(player, text().color(template))
        }
    }
}
