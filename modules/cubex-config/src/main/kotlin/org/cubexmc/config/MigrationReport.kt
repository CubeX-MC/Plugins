package org.cubexmc.config

import java.io.File

class MigrationReport internal constructor(
    private val planNameValue: String,
    private val resourcePathValue: String,
    private val fromVersionValue: Int,
    private val toVersionValue: Int,
    private val migratedValue: Boolean,
    private val skippedValue: Boolean,
    private val failedValue: Boolean,
    private val backupFileValue: File?,
    warnings: List<String>,
    failures: List<String>,
) {
    private val warningValues = warnings.toList()
    private val failureValues = failures.toList()
    fun planName(): String = planNameValue
    fun resourcePath(): String = resourcePathValue
    fun fromVersion(): Int = fromVersionValue
    fun toVersion(): Int = toVersionValue
    fun migrated(): Boolean = migratedValue
    fun skipped(): Boolean = skippedValue
    fun failed(): Boolean = failedValue
    fun backupFile(): File? = backupFileValue
    fun warnings(): List<String> = warningValues
    fun failures(): List<String> = failureValues
}
