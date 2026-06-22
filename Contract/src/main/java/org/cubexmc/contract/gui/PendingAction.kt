package org.cubexmc.contract.gui

import org.cubexmc.contract.model.Contract

/** A signed-off action the player previews on the confirmation screen before it executes. */
internal class PendingAction(
    private val kind: Kind,
    private val contractId: String?,
    private val arg: String?,
    private val title: String,
    private val consequences: List<String>,
) {
    enum class Kind { CREATE, ACCEPT, APPROVE, RESOLVE, MEDIATE, CANCEL, ADMIN_PAY, ADMIN_REFUND, ADMIN_CLOSE }

    fun kind(): Kind = kind
    fun contractId(): String = contractId ?: throw NullPointerException("contractId")
    fun arg(): String = arg ?: throw NullPointerException("arg")
    fun title(): String = title
    fun consequences(): List<String> = consequences

    companion object {
        fun simple(kind: Kind, contract: Contract, arg: String?, title: String, consequences: List<String>): PendingAction =
            PendingAction(kind, contract.id(), arg, title, consequences)

        fun create(draft: CreateDraft, preview: List<String>): PendingAction {
            val lines = ArrayList<String>()
            lines.add("即将创建一份${draft.type().name}合同。")
            lines.addAll(preview)
            return PendingAction(Kind.CREATE, null, null, "创建合同", lines)
        }
    }
}
