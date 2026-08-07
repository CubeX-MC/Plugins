package org.cubexmc.contract.service

import org.cubexmc.contract.model.BatchRepeatPolicy

object BatchRepeatRules {
    @JvmStatic
    fun evaluate(
        policy: BatchRepeatPolicy,
        hasActiveContract: Boolean,
        lastAcceptedAt: Long?,
        now: Long,
        cooldownMillis: Long,
    ): Decision {
        if (policy == BatchRepeatPolicy.UNLIMITED) {
            return Decision.allowed()
        }
        if (hasActiveContract) {
            return Decision.blocked(BlockReason.ACTIVE_CONTRACT)
        }
        if (lastAcceptedAt == null) {
            return Decision.allowed()
        }
        if (policy == BatchRepeatPolicy.ONCE) {
            return Decision.blocked(BlockReason.ALREADY_ACCEPTED)
        }
        val remaining = lastAcceptedAt + cooldownMillis - now
        return if (remaining > 0) {
            Decision.blocked(BlockReason.COOLDOWN, remaining)
        } else {
            Decision.allowed()
        }
    }

    enum class BlockReason {
        ACTIVE_CONTRACT,
        ALREADY_ACCEPTED,
        COOLDOWN,
    }

    data class Decision(
        val allowed: Boolean,
        val reason: BlockReason?,
        val remainingMillis: Long,
    ) {
        companion object {
            fun allowed(): Decision = Decision(true, null, 0L)

            fun blocked(reason: BlockReason, remainingMillis: Long = 0L): Decision =
                Decision(false, reason, remainingMillis.coerceAtLeast(0L))
        }
    }
}
