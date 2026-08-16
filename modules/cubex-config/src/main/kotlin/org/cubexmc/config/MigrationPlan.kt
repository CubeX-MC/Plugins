package org.cubexmc.config

class MigrationPlan private constructor(name: String?, private val resourcePathValue: String) {
    private val nameValue = name?.takeUnless { it.isBlank() } ?: resourcePathValue
    private var versionKeyValue = "version"
    private var missingVersionValue = 1
    private var targetVersionValue = 1
    private var backupDirectoryValue = "backups/migrations"
    private var restoreBackupOnSaveFailureValue = true
    private var failurePolicyValue = MigrationFailurePolicy.ABORT
    private val stepValues = ArrayList<MigrationStep>()

    fun versionKey(key: String?): MigrationPlan = apply { versionKeyValue = key?.takeUnless { it.isBlank() } ?: "version" }
    fun missingVersion(version: Int): MigrationPlan = apply { missingVersionValue = version }
    fun targetVersion(version: Int): MigrationPlan = apply { targetVersionValue = version }
    fun backupDirectory(relativePath: String?): MigrationPlan = apply {
        backupDirectoryValue = relativePath?.takeUnless { it.isBlank() } ?: "backups/migrations"
    }
    fun restoreBackupOnSaveFailure(enabled: Boolean): MigrationPlan = apply { restoreBackupOnSaveFailureValue = enabled }
    fun failurePolicy(policy: MigrationFailurePolicy?): MigrationPlan = apply {
        failurePolicyValue = policy ?: MigrationFailurePolicy.ABORT
    }
    fun addStep(step: MigrationStep?): MigrationPlan = apply { if (step != null) stepValues.add(step) }

    fun name(): String = nameValue
    fun resourcePath(): String = resourcePathValue
    fun versionKey(): String = versionKeyValue
    fun missingVersion(): Int = missingVersionValue
    fun targetVersion(): Int = targetVersionValue
    fun backupDirectory(): String = backupDirectoryValue
    fun restoreBackupOnSaveFailure(): Boolean = restoreBackupOnSaveFailureValue
    fun failurePolicy(): MigrationFailurePolicy = failurePolicyValue
    fun steps(): List<MigrationStep> = stepValues.toList()

    companion object {
        @JvmStatic
        fun yaml(name: String?, resourcePath: String): MigrationPlan = MigrationPlan(name, resourcePath)
    }
}
