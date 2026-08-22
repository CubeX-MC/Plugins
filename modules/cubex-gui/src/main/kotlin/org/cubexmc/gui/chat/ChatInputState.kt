package org.cubexmc.gui.chat

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 一次提问的结果。 */
sealed interface ChatOutcome {
    /** 玩家输入了一行（原始文本，未 trim）。 */
    class Submitted(val text: String) : ChatOutcome

    /** 玩家输入了 `clear` 关键字（仅当提问允许清空时才可能出现）。 */
    object Cleared : ChatOutcome

    /** 玩家输入了 `cancel`。 */
    object Cancelled : ChatOutcome

    /** 超时前没有任何输入。 */
    object TimedOut : ChatOutcome
}

/** [ChatInputState.accept] 的处理结论。[T] 是登记提问时携带的载荷（通常是回调）。 */
sealed interface AcceptResult<out T> {
    /** 这行不归我们管，放行给公屏。 */
    object NotOurs : AcceptResult<Nothing>

    /**
     * 同一行已经被**另一条聊天事件链路**取走过。必须照样吞掉（否则公屏会回显），
     * 但**不要**再跑一次回调。
     */
    object AlreadyTaken : AcceptResult<Nothing>

    /** 这行归我们，吞掉并把 [outcome] 投递给 [payload]。 */
    class Accepted<out T>(val outcome: ChatOutcome, val payload: T) : AcceptResult<T>
}

/**
 * 聊天提问的**纯状态机**：不碰 Bukkit，因此可以被单测完整覆盖。
 *
 * 平台相关的部分（监听哪几个聊天事件、怎么回主线程）留在插件侧，见 [ChatInput] 的说明——
 * 共享模块编译到 spigot-api 1.18，引不进 Paper 的 `AsyncChatEvent`。
 *
 * 去重的形状取自 Regions（全仓唯一把两条链路都处理对了的实现）：Paper 只在**没有**插件监听
 * legacy 事件时才走 `AsyncChatEvent`，一旦有人监听 legacy，全服都改走 legacy。同时监听两个、
 * 并把已取走的那一行再吞一次，是唯一两种服务器形态下都正确的做法。
 */
class ChatInputState<T : Any>(
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * 取消关键字。做成 supplier 是因为有插件（EcoBalancer）把它放在语言文件里，
     * reload 之后必须跟着变；也有插件（Metro）要额外认本地化的“取消”。
     */
    private val cancelKeywords: () -> Collection<String> = { listOf(CANCEL_KEYWORD) },
    private val clearKeywords: () -> Collection<String> = { listOf(CLEAR_KEYWORD) },
) {

    private val prompts: MutableMap<UUID, Prompt<T>> = ConcurrentHashMap()
    private val consumedLines: MutableMap<UUID, String> = ConcurrentHashMap()

    /**
     * 登记一次提问。同一玩家的上一次提问会被顶掉 —— 载荷（回调）跟着提问一起走，
     * 所以不会出现"提问换了、回调还留着"的悬挂状态。
     */
    fun open(playerId: UUID, allowClear: Boolean, timeoutMillis: Long, payload: T): Prompt<T> {
        // timeoutMillis <= 0 表示永不超时(Metro/Railway 的提问本来就没有超时)。
        // 直接算 clock() + Long.MAX_VALUE 会溢出成负数,反而立刻判超时。
        val expiresAt = if (timeoutMillis <= 0L) Long.MAX_VALUE else clock() + timeoutMillis
        val prompt = Prompt(allowClear, expiresAt, payload)
        prompts[playerId] = prompt
        return prompt
    }

    /** 玩家发了一行聊天。返回该怎么处理它。 */
    fun accept(playerId: UUID, message: String): AcceptResult<T> {
        val prompt = prompts.remove(playerId)
            ?: return if (consumedLines[playerId] == message) AcceptResult.AlreadyTaken else AcceptResult.NotOurs

        consumedLines[playerId] = message
        val outcome = if (clock() >= prompt.expiresAt) ChatOutcome.TimedOut else classify(message, prompt.allowClear)
        return AcceptResult.Accepted(outcome, prompt.payload)
    }

    /**
     * 回调投递完之后调用：清掉去重记录。
     *
     * 必须**晚于** [accept] 一个主线程 tick —— 两条事件链路是在同一次发言里先后触发的，
     * 提前清掉就等于没去重。
     */
    fun settle(playerId: UUID) {
        consumedLines.remove(playerId)
    }

    /** 提问还在等待中吗（用于超时任务判断自己是否已被抢先处理）。 */
    fun isPending(playerId: UUID, prompt: Prompt<T>): Boolean = prompts[playerId] === prompt

    /** 超时：取走提问，返回它是否仍然有效。 */
    fun expire(playerId: UUID, prompt: Prompt<T>): Boolean = prompts.remove(playerId, prompt)

    /** 玩家退出 / 主动取消：忘掉一切。 */
    fun forget(playerId: UUID) {
        prompts.remove(playerId)
        consumedLines.remove(playerId)
    }

    fun forgetAll() {
        prompts.clear()
        consumedLines.clear()
    }

    private fun classify(message: String, allowClear: Boolean): ChatOutcome {
        val trimmed = message.trim()
        return when {
            trimmed.matchesAny(cancelKeywords()) -> ChatOutcome.Cancelled
            allowClear && trimmed.matchesAny(clearKeywords()) -> ChatOutcome.Cleared
            else -> ChatOutcome.Submitted(message)
        }
    }

    private fun String.matchesAny(keywords: Collection<String>): Boolean =
        keywords.any { it.isNotBlank() && this.equals(it, ignoreCase = true) }

    /** 一次提问的登记项。身份比较（`===`）用来判断超时任务是否已经过期。 */
    class Prompt<out T>(val allowClear: Boolean, val expiresAt: Long, val payload: T)

    companion object {
        const val CANCEL_KEYWORD: String = "cancel"
        const val CLEAR_KEYWORD: String = "clear"
    }
}
