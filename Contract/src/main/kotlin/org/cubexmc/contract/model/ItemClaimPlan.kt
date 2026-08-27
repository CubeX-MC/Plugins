package org.cubexmc.contract.model

import org.bukkit.inventory.ItemStack
import java.math.BigDecimal
import java.util.EnumMap

/**
 * Resolves physical item stakes to exactly one participant recipient per source role.
 *
 * Money may be split between a participant and the system sink for commission. Physical stacks
 * cannot be split by percentage, so a source with zero or multiple participant recipients fails
 * closed before any external payout is attempted.
 */
class ItemClaimPlan private constructor(
    claims: Map<ParticipantRole, Map<ParticipantRole, List<ItemStack>>>,
) {
    private val claims = copyClaims(claims)

    fun claims(): Map<ParticipantRole, Map<ParticipantRole, List<ItemStack>>> = copyClaims(claims)

    fun isEmpty(): Boolean = claims.isEmpty()

    companion object {
        @JvmStatic
        fun route(contract: Contract, rules: List<PayoutRule>): ItemClaimPlan {
            val routed = EnumMap<ParticipantRole, MutableMap<ParticipantRole, List<ItemStack>>>(ParticipantRole::class.java)
            for (source in contract.participants()) {
                val items = contract.escrowedItems(source.role())
                if (items.isEmpty()) continue

                val recipients = rules.asSequence()
                    .filter { it.source() == source.role() }
                    .filter { it.sharePercent() > BigDecimal.ZERO }
                    .filter { it.recipient().kind() == PayoutRecipient.Kind.PARTICIPANT }
                    .mapNotNull { it.recipient().role() }
                    .distinct()
                    .toList()
                require(recipients.size == 1) {
                    "Item stake from ${source.role()} requires exactly one participant recipient; found $recipients"
                }
                val bySource = routed.computeIfAbsent(recipients.single()) {
                    EnumMap(ParticipantRole::class.java)
                }
                bySource[source.role()] = items.map { it.clone() }
            }
            return ItemClaimPlan(routed)
        }

        private fun copyClaims(
            source: Map<ParticipantRole, Map<ParticipantRole, List<ItemStack>>>,
        ): Map<ParticipantRole, Map<ParticipantRole, List<ItemStack>>> {
            val copy = EnumMap<ParticipantRole, Map<ParticipantRole, List<ItemStack>>>(ParticipantRole::class.java)
            for ((recipient, claimsBySource) in source) {
                val sourceCopy = EnumMap<ParticipantRole, List<ItemStack>>(ParticipantRole::class.java)
                for ((sourceRole, items) in claimsBySource) {
                    sourceCopy[sourceRole] = items.map { it.clone() }
                }
                if (sourceCopy.isNotEmpty()) copy[recipient] = sourceCopy
            }
            return copy
        }
    }
}
