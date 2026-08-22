package org.cubexmc.economy

/**
 * 一次经济操作的结果。
 *
 * [success] 表达的始终是**玩家这一侧**成不成:扣款有没有真的扣到。
 * [depositFailed] 是入账那一侧的旁路信号 —— 钱从玩家身上扣走了、但没能转进
 * `economy.account`。这种情况**不回滚**(理由见 [VaultEconomy.charge]),
 * 调用方通常不需要处理它,但服主要能从日志里对上账。
 */
class EconomyResult private constructor(
    private val success: Boolean,
    private val reason: String,
    private val depositFailed: Boolean,
) {
    fun success(): Boolean = success

    fun reason(): String = reason

    /** 扣款成功但没能入账到 `economy.account`:这笔钱被销毁了,需要服主对账。 */
    fun depositFailed(): Boolean = depositFailed

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EconomyResult) return false
        return success == other.success && reason == other.reason && depositFailed == other.depositFailed
    }

    override fun hashCode(): Int = (31 * success.hashCode() + reason.hashCode()) * 31 + depositFailed.hashCode()

    override fun toString(): String =
        "EconomyResult[success=$success, reason=$reason, depositFailed=$depositFailed]"

    companion object {
        private val OK = EconomyResult(true, "", false)

        @JvmStatic
        fun ok(): EconomyResult = OK

        /** 扣款成功,入账失败。 */
        @JvmStatic
        fun okButNotBanked(reason: String?): EconomyResult =
            EconomyResult(true, reason.orEmpty().ifBlank { "deposit to economy.account failed" }, true)

        @JvmStatic
        fun fail(reason: String?): EconomyResult =
            EconomyResult(false, reason.orEmpty().ifBlank { "economy transaction failed" }, false)
    }
}
