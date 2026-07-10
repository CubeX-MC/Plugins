package org.cubexmc.contract.gui

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.cubexmc.contract.ContractPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Outcome of a single chat-line prompt. */
sealed interface ChatOutcome {
    /** Player typed a line (raw, untrimmed). */
    class Submitted(val text: String) : ChatOutcome

    /** Player typed the `clear` keyword (only offered when the prompt allows clearing). */
    object Cleared : ChatOutcome

    /** Player typed `cancel`. */
    object Cancelled : ChatOutcome

    /** No input arrived before the timeout elapsed. */
    object TimedOut : ChatOutcome
}

/**
 * Captures a single line of public-chat input from a player, with a timeout and `cancel`/`clear`
 * keywords. This is the low-version / fallback text-entry backend that replaces the old anvil GUIs;
 * the high-version Paper Dialog backend is layered on top in a later phase.
 *
 * Registered as its own [Listener] so the chat capture is isolated from the inventory GUI logic.
 */
class ChatInputService(private val plugin: ContractPlugin) : Listener {
    private val prompts: MutableMap<UUID, Prompt> = ConcurrentHashMap()

    /**
     * Closes any open inventory, sends [message] to the player, and waits for the next chat line.
     * [callback] runs on the main thread with the resulting [ChatOutcome].
     */
    fun promptLine(
        player: Player,
        message: String,
        allowClear: Boolean,
        timeoutMs: Long,
        callback: (ChatOutcome) -> Unit,
    ) {
        val playerId = player.uniqueId
        val prompt = Prompt(allowClear, System.currentTimeMillis() + timeoutMs, callback)
        prompts[playerId] = prompt
        player.closeInventory()
        player.sendMessage(message)
        plugin.scheduler().runAtEntityLater(player, Runnable {
            val current = prompts[playerId]
            if (current === prompt && System.currentTimeMillis() >= prompt.expiresAt) {
                prompts.remove(playerId)
                if (player.isOnline) {
                    prompt.callback(ChatOutcome.TimedOut)
                }
            }
        }, timeoutMs / 50L + 1L)
    }

    fun cancel(player: Player) {
        prompts.remove(player.uniqueId)
    }

    fun cancelAll() {
        prompts.clear()
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        val playerId = event.player.uniqueId
        val prompt = prompts.remove(playerId) ?: return
        event.isCancelled = true
        val message = event.message
        plugin.scheduler().runAtEntity(event.player, Runnable {
            if (System.currentTimeMillis() >= prompt.expiresAt) {
                prompt.callback(ChatOutcome.TimedOut)
                return@Runnable
            }
            val trimmed = message.trim()
            val outcome = when {
                trimmed.equals("cancel", ignoreCase = true) -> ChatOutcome.Cancelled
                prompt.allowClear && trimmed.equals("clear", ignoreCase = true) -> ChatOutcome.Cleared
                else -> ChatOutcome.Submitted(message)
            }
            prompt.callback(outcome)
        })
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        prompts.remove(event.player.uniqueId)
    }

    private class Prompt(
        val allowClear: Boolean,
        val expiresAt: Long,
        val callback: (ChatOutcome) -> Unit,
    )
}
