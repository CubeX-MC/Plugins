package org.cubexmc.contract.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.LinkedHashMap
import java.util.Objects

/**
 * 描述某个 condition 触发时,从哪个 source 把多少份额转给哪个 recipient.
 * sharePercent 是源 stake 池的百分比(0-100)。
 */
class PayoutRule(
    private val condition: PayoutCondition,
    private val source: ParticipantRole,
    private val recipient: PayoutRecipient,
    private val sharePercent: BigDecimal,
) {
    fun condition(): PayoutCondition = condition

    fun source(): ParticipantRole = source

    fun recipient(): PayoutRecipient = recipient

    fun sharePercent(): BigDecimal = sharePercent

    fun applyTo(sourceAmount: BigDecimal): BigDecimal =
        sourceAmount.multiply(sharePercent).divide(HUNDRED, 2, RoundingMode.HALF_UP)

    fun toMap(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["condition"] = condition.name
        map["source"] = source.name
        map["recipient"] = recipient.toMap()
        map["share-percent"] = sharePercent.toPlainString()
        return map
    }

    companion object {
        private val HUNDRED = BigDecimal("100")

        @JvmStatic
        fun fromMap(map: Map<*, *>): PayoutRule {
            val condition = PayoutCondition.valueOf(Objects.toString(map["condition"], "SUCCESS"))
            val source = ParticipantRole.valueOf(Objects.toString(map["source"], "OWNER"))
            val recipient = when (val rec = map["recipient"]) {
                is Map<*, *> -> PayoutRecipient.fromMap(rec)
                else -> PayoutRecipient.systemSink()
            }
            val share = map["share-percent"]
            val value = if (share == null) BigDecimal.ZERO else BigDecimal(share.toString())
            return PayoutRule(condition, source, recipient, value)
        }
    }
}
