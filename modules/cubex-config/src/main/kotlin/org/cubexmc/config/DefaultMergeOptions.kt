package org.cubexmc.config

import java.io.File
import java.util.function.Function

public class DefaultMergeOptions private constructor() {
    private var backupBeforeSaveValue = false
    private var saveWhenChangedValue = true
    private var includeSectionsValue = false
    private var warnAboutCommentLossValue = true
    private var failOnErrorValue = false
    private var backupFunctionValue: Function<File, File?>? = null

    public fun backupBeforeSave(enabled: Boolean): DefaultMergeOptions = apply { backupBeforeSaveValue = enabled }
    public fun saveWhenChanged(enabled: Boolean): DefaultMergeOptions = apply { saveWhenChangedValue = enabled }
    public fun includeSections(enabled: Boolean): DefaultMergeOptions = apply { includeSectionsValue = enabled }
    public fun warnAboutCommentLoss(enabled: Boolean): DefaultMergeOptions = apply { warnAboutCommentLossValue = enabled }
    public fun isBackupBeforeSave(): Boolean = backupBeforeSaveValue
    public fun isSaveWhenChanged(): Boolean = saveWhenChangedValue
    public fun isIncludeSections(): Boolean = includeSectionsValue
    public fun isWarnAboutCommentLoss(): Boolean = warnAboutCommentLossValue
    public fun failOnError(enabled: Boolean): DefaultMergeOptions = apply { failOnErrorValue = enabled }
    public fun backupWith(backup: Function<File, File?>): DefaultMergeOptions = apply {
        backupBeforeSaveValue = true
        backupFunctionValue = backup
    }
    internal fun failOnError(): Boolean = failOnErrorValue
    internal fun backupFunction(): Function<File, File?>? = backupFunctionValue

    public companion object {
        @JvmStatic
        public fun copyMissingKeys(): DefaultMergeOptions = DefaultMergeOptions()
    }
}
