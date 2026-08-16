package org.cubexmc.config

import java.io.File

class DefaultMergeResult internal constructor(
    private val changedValue: Boolean,
    addedKeys: List<String>,
    private val backupFileValue: File?,
) {
    private val addedKeyValues = addedKeys.toList()
    fun changed(): Boolean = changedValue
    fun addedKeys(): List<String> = addedKeyValues
    fun backupFile(): File? = backupFileValue
}
