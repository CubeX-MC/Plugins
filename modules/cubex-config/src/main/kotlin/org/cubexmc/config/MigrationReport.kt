package org.cubexmc.config

import java.io.File

public class MigrationReport internal constructor(
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
    public fun planName(): String = planNameValue
    public fun resourcePath(): String = resourcePathValue
    public fun fromVersion(): Int = fromVersionValue
    public fun toVersion(): Int = toVersionValue
    public fun migrated(): Boolean = migratedValue
    public fun skipped(): Boolean = skippedValue
    public fun failed(): Boolean = failedValue
    public fun backupFile(): File? = backupFileValue
    public fun warnings(): List<String> = warningValues
    public fun failures(): List<String> = failureValues
}
