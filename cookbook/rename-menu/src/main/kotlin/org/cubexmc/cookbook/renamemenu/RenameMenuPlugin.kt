package org.cubexmc.cookbook.renamemenu

import org.bukkit.Material
import org.bukkit.command.CommandExecutor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.cubexmc.core.CubexPlugin
import org.cubexmc.gui.ItemBuilder
import org.cubexmc.gui.Menu
import org.cubexmc.gui.MenuRegistry
import org.cubexmc.gui.chat.AcceptResult
import org.cubexmc.gui.chat.ChatInputState
import org.cubexmc.gui.chat.ChatOutcome
import org.cubexmc.gui.chat.ModernChatBridge
import org.cubexmc.gui.fillEmpty
import org.cubexmc.scheduler.CubexScheduler

/**
 * Cookbook 05 —— **改名菜单**。演示 `Menu` + `fillEmpty` + `ChatInputState`。
 *
 * 看点：
 * 1. 每个按钮**自带点击处理**，界面里没有中央的 `when (slot)` 分发。
 * 2. [fillEmpty] 在**摆完按钮之后**调用 —— 顺序反了会把还没放的位置提前占掉。
 * 3. 聊天提问的状态机来自模块：超时、`cancel` 关键字、两条聊天链路的去重都不用自己写。
 *    插件侧只剩三件平台相关的事：监听哪些事件、怎么回主线程、拿到结果做什么。
 * 4. 现代聊天事件经 [ModernChatBridge] 反射注册 —— Paper 上两条链路都接住，Spigot 上自动跳过。
 */
class RenameMenuPlugin : CubexPlugin(), Listener {

    private val menus = MenuRegistry()
    private val prompts = ChatInputState<Player>()
    private lateinit var scheduler: CubexScheduler

    override fun enablePlugin() {
        scheduler = CubexScheduler.bindTo(this)
        registerListener(menus)
        registerListener(this)

        ModernChatBridge.register(this) { player, message -> capture(player, message) }
            ?.let { listener -> bind(Runnable { ModernChatBridge.unregister(listener) }) }

        registerCommand("renamemenu", CommandExecutor { sender, _, _, _ ->
            (sender as? Player)?.let { menus.open(it, buildMenu(it)) }
            true
        })
    }

    private fun buildMenu(player: Player): Menu {
        val menu = Menu(text().color("&8改名"), rows = 3)
        menu.button(
            slot = 13,
            icon = ItemBuilder(Material.NAME_TAG).name(text().color("&e点击输入新名字")).build(),
        ) {
            // 30 秒超时;输入 cancel 取消
            prompts.open(player.uniqueId, allowClear = false, timeoutMillis = 30_000L, payload = player)
            player.closeInventory()
            messager().send(player, text().color("&7请在聊天栏输入新名字,输入 &fcancel &7取消。"))
        }
        // 摆完按钮之后再铺底
        menu.fillEmpty(ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(" ").build())
        return menu
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onLegacyChat(event: AsyncPlayerChatEvent) {
        if (capture(event.player, event.message)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        prompts.forget(event.player.uniqueId)
    }

    private fun capture(player: Player, message: String): Boolean {
        val playerId = player.uniqueId
        return when (val result = prompts.accept(playerId, message)) {
            AcceptResult.NotOurs -> false
            AcceptResult.AlreadyTaken -> true
            is AcceptResult.Accepted -> {
                // runAtEntity 有 Runnable / Consumer<CubexTask> 两个重载,尾随 lambda 会有歧义,
                // 所以显式写出 Runnable。
                scheduler.runAtEntity(
                    player,
                    Runnable {
                        prompts.settle(playerId)
                        deliver(result.payload, result.outcome)
                    },
                )
                true
            }
        }
    }

    private fun deliver(player: Player, outcome: ChatOutcome) {
        when (outcome) {
            ChatOutcome.Cancelled -> messager().send(player, text().color("&7已取消。"))
            ChatOutcome.TimedOut -> messager().send(player, text().color("&7超时,已取消。"))
            is ChatOutcome.Submitted -> rename(player, outcome.text)
            else -> Unit
        }
    }

    private fun rename(player: Player, raw: String) {
        val name = NameRules.sanitise(raw)
        if (name == null) {
            messager().send(player, text().color("&c名字不能为空。"))
            return
        }
        val held = player.inventory.itemInMainHand
        val meta = held.itemMeta
        if (meta == null) {
            messager().send(player, text().color("&c手上没有可改名的物品。"))
            return
        }
        meta.setDisplayName(text().color(name))
        held.itemMeta = meta
        messager().send(player, text().color("&a已改名为 &f$name"))
    }
}
