package org.cubexmc.contract.gui

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.cubexmc.contract.ContractPlugin
import org.cubexmc.gui.chat.AcceptResult
import org.cubexmc.gui.chat.ChatInputState
import org.cubexmc.gui.chat.ChatOutcome

/**
 * Contract 侧的聊天提问适配层。
 *
 * 提问、超时、`cancel`/`clear` 关键字、两条聊天链路的去重，全部已经下沉到 `cubex-gui` 的
 * [ChatInputState]（有 11 条单测）。这里只剩三件平台相关的事：监听哪几个事件、怎么回主线程、
 * 把结果交给调用方。调用方（[ContractGui] / [DialogSupport]）的 API 与下沉前完全一致。
 */
class ChatInputService(private val plugin: ContractPlugin) : Listener {

    private val state = ChatInputState<(ChatOutcome) -> Unit>()

    /**
     * 关掉当前界面、发出 [message]，然后等待下一行聊天。
     * [callback] 在主线程上以对应的 [ChatOutcome] 运行。
     */
    fun promptLine(
        player: Player,
        message: String,
        allowClear: Boolean,
        timeoutMs: Long,
        callback: (ChatOutcome) -> Unit,
    ) {
        val playerId = player.uniqueId
        val prompt = state.open(playerId, allowClear, timeoutMs, callback)
        player.closeInventory()
        player.sendMessage(message)
        plugin.scheduler().runAtEntityLater(player, Runnable {
            // expire 只在这次提问仍是当前提问时才成功 —— 玩家中途重新提问时旧的超时任务就此作废。
            if (state.expire(playerId, prompt) && player.isOnline) {
                callback(ChatOutcome.TimedOut)
            }
        }, timeoutMs / 50L + 1L)
    }

    fun cancel(player: Player) {
        state.forget(player.uniqueId)
    }

    fun cancelAll() {
        state.forgetAll()
    }

    /** Paper 的现代聊天事件。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val message = PlainTextComponentSerializer.plainText().serialize(event.message())
        if (capture(event.player, message)) {
            event.isCancelled = true
        }
    }

    /**
     * 旧聊天事件。**两个都必须监听**：Paper 只在没有任何插件监听 legacy 事件时才走
     * [AsyncChatEvent]，一旦有人监听 legacy（CMI，以及本仓库的 EcoBalancer / Metro / Railway
     * 都在监听），全服就改走 legacy 链路，只监听现代事件的插件一次都收不到 ——
     * 表现为提示词收不到输入、玩家的回答被广播到公屏。
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onLegacyChat(event: AsyncPlayerChatEvent) {
        if (capture(event.player, event.message)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        state.forget(event.player.uniqueId)
    }

    /** 返回这行是否归 Contract、必须挡在公屏之外。 */
    private fun capture(player: Player, message: String): Boolean {
        val playerId = player.uniqueId
        return when (val result = state.accept(playerId, message)) {
            AcceptResult.NotOurs -> false
            // 同一行从另一条链路又来了一遍:照样吞掉,但不重复跑回调。
            AcceptResult.AlreadyTaken -> true
            is AcceptResult.Accepted -> {
                plugin.scheduler().runAtEntity(player, Runnable {
                    state.settle(playerId)
                    result.payload(result.outcome)
                })
                true
            }
        }
    }
}
