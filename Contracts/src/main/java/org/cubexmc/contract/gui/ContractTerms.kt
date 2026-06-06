package org.cubexmc.contract.gui

internal object ContractTerms {
    @JvmStatic
    fun preview(description: String?): String {
        var clean = description?.trim() ?: ""
        if (clean.isEmpty()) {
            return "未填写"
        }
        clean = clean.replace(Regex("\\R+"), " / ")
        return if (clean.length <= 48) clean else clean.substring(0, 45) + "..."
    }
}
