package org.cubexmc.config

class DefaultMergeOptions private constructor() {
    private var backupBeforeSaveValue = false
    private var saveWhenChangedValue = true
    private var includeSectionsValue = false
    private var warnAboutCommentLossValue = true

    fun backupBeforeSave(enabled: Boolean): DefaultMergeOptions = apply { backupBeforeSaveValue = enabled }
    fun saveWhenChanged(enabled: Boolean): DefaultMergeOptions = apply { saveWhenChangedValue = enabled }
    fun includeSections(enabled: Boolean): DefaultMergeOptions = apply { includeSectionsValue = enabled }
    fun warnAboutCommentLoss(enabled: Boolean): DefaultMergeOptions = apply { warnAboutCommentLossValue = enabled }
    fun isBackupBeforeSave(): Boolean = backupBeforeSaveValue
    fun isSaveWhenChanged(): Boolean = saveWhenChangedValue
    fun isIncludeSections(): Boolean = includeSectionsValue
    fun isWarnAboutCommentLoss(): Boolean = warnAboutCommentLossValue

    companion object {
        @JvmStatic
        fun copyMissingKeys(): DefaultMergeOptions = DefaultMergeOptions()
    }
}
