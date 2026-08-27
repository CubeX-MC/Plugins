package org.cubexmc.contract.gui

import org.bukkit.inventory.ItemStack
import org.cubexmc.contract.model.Contract

/** A signed-off action the player previews on the confirmation screen before it executes. */
internal class PendingAction(
    private val kind: Kind,
    private val contractId: String?,
    private val arg: String?,
    private val title: String,
    private val consequences: List<String>,
    expectedSaleItem: ItemStack? = null,
) {
    private val expectedSaleItem = expectedSaleItem?.clone()
    enum class Kind { CREATE, ACCEPT, ACCEPT_BATCH, APPROVE, RESOLVE, MEDIATE, CANCEL, ADMIN_PAY, ADMIN_REFUND, ADMIN_CLOSE }

    fun kind(): Kind = kind
    fun contractId(): String = contractId ?: throw NullPointerException("contractId")
    fun arg(): String = arg ?: throw NullPointerException("arg")
    fun title(): String = title
    fun consequences(): List<String> = consequences
    fun expectedSaleItem(): ItemStack? = expectedSaleItem?.clone()

    companion object {
        fun simple(kind: Kind, contract: Contract, arg: String?, title: String, consequences: List<String>): PendingAction =
            PendingAction(kind, contract.id(), arg, title, consequences)

        /** [title] and [lead] arrive already localized; this class never resolves language keys. */
        fun create(title: String, lead: String, preview: List<String>, expectedSaleItem: ItemStack? = null): PendingAction {
            val lines = ArrayList<String>()
            lines.add(lead)
            lines.addAll(preview)
            return PendingAction(Kind.CREATE, null, null, title, lines, expectedSaleItem)
        }
    }
}
