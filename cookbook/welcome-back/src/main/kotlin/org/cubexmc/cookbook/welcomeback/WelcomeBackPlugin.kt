package org.cubexmc.cookbook.welcomeback

import org.bukkit.event.player.PlayerJoinEvent
import org.cubexmc.core.CubexPlugin
import org.cubexmc.core.onEvent

/**
 * Cookbook 02 —— `onEvent` 的自动绑定。
 *
 * 对比一下手写的等价物：`object : Listener {}` + `@EventHandler` + `registerEvents` +
 * **别忘了** `bind { HandlerList.unregisterAll(listener) }`。最后那一步漏掉时插件照常工作，
 * 直到某次 reload 之后监听器重复触发 —— 而且不会有任何报错指向真正的原因。
 *
 * `onEvent` 注册完就自动绑进资源栈，让这一步不可能被漏掉。它返回的 `Terminable`
 * 可以用来提前注销（一次性监听），不用也没关系。
 */
class WelcomeBackPlugin : CubexPlugin() {

    override fun enablePlugin() {
        onEvent<PlayerJoinEvent> { event ->
            val message = Welcomes.render(event.player.name, event.player.hasPlayedBefore().not())
            messager().send(event.player, text().color(message))
        }
    }
}
