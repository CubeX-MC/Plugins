package org.cubexmc.config

import java.io.File

public class DefaultMergeResult internal constructor(
    private val changedValue: Boolean,
    addedKeys: List<String>,
    private val backupFileValue: File?,
) {
    private val addedKeyValues = addedKeys.toList()
    public fun changed(): Boolean = changedValue
    public fun addedKeys(): List<String> = addedKeyValues
    public fun backupFile(): File? = backupFileValue
}
