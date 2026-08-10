package org.cubexmc.contract.gui

internal object ContractTerms {
    /** [empty] is the caller's localized "not filled in yet" label. */
    @JvmStatic
    fun preview(description: String?, empty: String): String {
        var clean = description?.trim() ?: ""
        if (clean.isEmpty()) {
            return empty
        }
        clean = clean.replace(Regex("\\R+"), " / ")
        return if (clean.length <= 48) clean else clean.substring(0, 45) + "..."
    }
}
