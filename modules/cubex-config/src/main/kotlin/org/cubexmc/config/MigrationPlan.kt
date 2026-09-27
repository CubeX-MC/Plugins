package org.cubexmc.config

public class MigrationPlan private constructor(name: String?, private val resourcePathValue: String) {
    private val nameValue = name?.takeUnless { it.isBlank() } ?: resourcePathValue
    private var versionKeyValue = "version"
    private var missingVersionValue = 1
    private var targetVersionValue = 1
    private var backupDirectoryValue = "backups/migrations"
    private var restoreBackupOnSaveFailureValue = true
    private var failurePolicyValue = MigrationFailurePolicy.ABORT
    private val stepValues = ArrayList<MigrationStep>()

    public fun versionKey(key: String?): MigrationPlan = apply { versionKeyValue = key?.takeUnless { it.isBlank() } ?: "version" }
    public fun missingVersion(version: Int): MigrationPlan = apply { missingVersionValue = version }
    public fun targetVersion(version: Int): MigrationPlan = apply { targetVersionValue = version }
    public fun backupDirectory(relativePath: String?): MigrationPlan = apply {
        backupDirectoryValue = relativePath?.takeUnless { it.isBlank() } ?: "backups/migrations"
    }
    public fun restoreBackupOnSaveFailure(enabled: Boolean): MigrationPlan = apply { restoreBackupOnSaveFailureValue = enabled }
    public fun failurePolicy(policy: MigrationFailurePolicy?): MigrationPlan = apply {
        failurePolicyValue = policy ?: MigrationFailurePolicy.ABORT
    }
    public fun addStep(step: MigrationStep?): MigrationPlan = apply { if (step != null) stepValues.add(step) }

    public fun name(): String = nameValue
    public fun resourcePath(): String = resourcePathValue
    public fun versionKey(): String = versionKeyValue
    public fun missingVersion(): Int = missingVersionValue
    public fun targetVersion(): Int = targetVersionValue
    public fun backupDirectory(): String = backupDirectoryValue
    public fun restoreBackupOnSaveFailure(): Boolean = restoreBackupOnSaveFailureValue
    public fun failurePolicy(): MigrationFailurePolicy = failurePolicyValue
    public fun steps(): List<MigrationStep> = stepValues.toList()

    public companion object {
        @JvmStatic
        public fun yaml(name: String?, resourcePath: String): MigrationPlan = MigrationPlan(name, resourcePath)
    }
}
