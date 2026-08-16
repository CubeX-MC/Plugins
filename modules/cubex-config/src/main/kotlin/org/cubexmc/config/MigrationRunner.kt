package org.cubexmc.config

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.core.CubexPlugin
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.Level

class MigrationRunner(private val plugin: CubexPlugin) {
    @Throws(MigrationException::class)
    fun run(plan: MigrationPlan?): MigrationReport {
        if (plan == null) throw MigrationException("Migration plan is null.")
        val file = File(plugin.dataFolder, plan.resourcePath())
        val yaml = YamlConfiguration.loadConfiguration(file)
        val currentVersion =
            if (yaml.isSet(plan.versionKey())) yaml.getInt(plan.versionKey(), plan.missingVersion())
            else plan.missingVersion()
        val warnings = ArrayList<String>()
        val failures = ArrayList<String>()

        if (currentVersion > plan.targetVersion()) {
            plugin.logger.warning(
                "Skipping ${plan.resourcePath()} migration: file version $currentVersion " +
                    "is newer than target ${plan.targetVersion()}.",
            )
            return MigrationReport(
                plan.name(), plan.resourcePath(), currentVersion, plan.targetVersion(),
                false, true, false, null, warnings, failures,
            )
        }
        if (currentVersion == plan.targetVersion()) {
            return MigrationReport(
                plan.name(), plan.resourcePath(), currentVersion, plan.targetVersion(),
                false, true, false, null, warnings, failures,
            )
        }

        var backupFile: File? = null
        try {
            backupFile = backup(file, plan)
            val context = SimpleMigrationContext(file, plan.resourcePath(), yaml, warnings, failures)
            var version = currentVersion
            for (step in orderedSteps(plan)) {
                if (step.fromVersion() == version && step.toVersion() <= plan.targetVersion()) {
                    step.migrate(context)
                    version = step.toVersion()
                }
            }
            if (version != plan.targetVersion()) {
                context.fail(
                    plan.versionKey(),
                    "No migration path from $currentVersion to ${plan.targetVersion()}.",
                )
            }
            if (failures.isNotEmpty() && plan.failurePolicy() == MigrationFailurePolicy.ABORT) {
                throw MigrationException("Migration failed for ${plan.resourcePath()}: $failures")
            }
            yaml[plan.versionKey()] = plan.targetVersion()
            saveAtomically(file, yaml, backupFile, plan.restoreBackupOnSaveFailure())
            logReport(plan, currentVersion, backupFile, warnings)
            return MigrationReport(
                plan.name(), plan.resourcePath(), currentVersion, plan.targetVersion(),
                true, false, false, backupFile, warnings, failures,
            )
        } catch (exception: MigrationException) {
            logFailure(plan, failures, exception)
            returnOrThrow(plan, exception)
        } catch (exception: Exception) {
            val wrapped = MigrationException("Migration failed for ${plan.resourcePath()}", exception)
            logFailure(plan, failures, wrapped)
            returnOrThrow(plan, wrapped)
        }
        return MigrationReport(
            plan.name(), plan.resourcePath(), currentVersion, plan.targetVersion(),
            false, false, true, backupFile, warnings, failures,
        )
    }

    private fun orderedSteps(plan: MigrationPlan): List<MigrationStep> =
        plan.steps().sortedWith(compareBy(MigrationStep::fromVersion).thenBy(MigrationStep::toVersion))

    private fun backup(file: File, plan: MigrationPlan): File? {
        if (!file.exists()) return null
        val relative = Path.of(plan.resourcePath())
        val backupRoot = plugin.dataFolder.toPath()
            .resolve(plan.backupDirectory())
            .resolve(LocalDateTime.now().format(BACKUP_TIMESTAMP))
        val backupPath = backupRoot.resolve(relative)
        Files.createDirectories(backupPath.parent)
        Files.copy(
            file.toPath(), backupPath,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
        )
        return backupPath.toFile()
    }

    private fun saveAtomically(
        file: File,
        yaml: YamlConfiguration,
        backupFile: File?,
        restoreBackup: Boolean,
    ) {
        val target = file.toPath()
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, file.name, ".tmp")
        try {
            yaml.save(temp.toFile())
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (exception: Exception) {
            Files.deleteIfExists(temp)
            if (restoreBackup && backupFile?.exists() == true) {
                Files.copy(backupFile.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            }
            throw exception
        }
    }

    private fun logReport(plan: MigrationPlan, currentVersion: Int, backupFile: File?, warnings: List<String>) {
        plugin.logger.info(
            "Migrated ${plan.resourcePath()} from v$currentVersion to v${plan.targetVersion()}" +
                if (backupFile == null) "." else " (backup: ${backupFile.path}).",
        )
        warnings.forEach(plugin.logger::warning)
    }

    private fun logFailure(plan: MigrationPlan, failures: List<String>, exception: MigrationException) {
        failures.forEach(plugin.logger::severe)
        plugin.logger.log(Level.SEVERE, "Failed to migrate ${plan.resourcePath()}.", exception)
    }

    @Throws(MigrationException::class)
    private fun returnOrThrow(plan: MigrationPlan, exception: MigrationException) {
        if (plan.failurePolicy() == MigrationFailurePolicy.ABORT) throw exception
    }

    private class SimpleMigrationContext(
        private val fileValue: File,
        private val resourcePathValue: String,
        private val yamlValue: YamlConfiguration,
        private val warnings: MutableList<String>,
        private val failures: MutableList<String>,
    ) : MigrationContext {
        override fun file(): File = fileValue
        override fun resourcePath(): String = resourcePathValue
        override fun yaml(): YamlConfiguration = yamlValue
        override fun warning(path: String?, message: String?) {
            warnings.add("$resourcePathValue:$path: $message")
        }
        override fun fail(path: String?, message: String?) {
            failures.add("$resourcePathValue:$path: $message")
        }
    }

    private companion object {
        val BACKUP_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
