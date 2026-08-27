package org.cubexmc.config

import java.io.File
import java.util.function.Function

class DefaultMergeOptions private constructor() {
    private var backupBeforeSaveValue = false
    private var saveWhenChangedValue = true
    private var includeSectionsValue = false
    private var warnAboutCommentLossValue = true
    private var failOnErrorValue = false
    private var backupFunctionValue: Function<File, File?>? = null

    fun backupBeforeSave(enabled: Boolean): DefaultMergeOptions = apply { backupBeforeSaveValue = enabled }
    fun saveWhenChanged(enabled: Boolean): DefaultMergeOptions = apply { saveWhenChangedValue = enabled }
    fun includeSections(enabled: Boolean): DefaultMergeOptions = apply { includeSectionsValue = enabled }
    fun warnAboutCommentLoss(enabled: Boolean): DefaultMergeOptions = apply { warnAboutCommentLossValue = enabled }
    fun isBackupBeforeSave(): Boolean = backupBeforeSaveValue
    fun isSaveWhenChanged(): Boolean = saveWhenChangedValue
    fun isIncludeSections(): Boolean = includeSectionsValue
    fun isWarnAboutCommentLoss(): Boolean = warnAboutCommentLossValue
    fun failOnError(enabled: Boolean): DefaultMergeOptions = apply { failOnErrorValue = enabled }
    fun backupWith(backup: Function<File, File?>): DefaultMergeOptions = apply {
        backupBeforeSaveValue = true
        backupFunctionValue = backup
    }
    internal fun failOnError(): Boolean = failOnErrorValue
    internal fun backupFunction(): Function<File, File?>? = backupFunctionValue

    companion object {
        @JvmStatic
        fun copyMissingKeys(): DefaultMergeOptions = DefaultMergeOptions()
    }
}
