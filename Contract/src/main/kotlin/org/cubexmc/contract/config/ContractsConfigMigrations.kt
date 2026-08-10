package org.cubexmc.contract.config

import org.cubexmc.config.MigrationContext
import org.cubexmc.config.MigrationStep

object ContractsConfigMigrations {
    /** v5 -> v6: configure reusable templates and one-time scheduled publication. */
    @JvmStatic
    fun schedulingAndTemplatesStep(): MigrationStep = object : MigrationStep {
        override fun fromVersion(): Int = 5
        override fun toVersion(): Int = 6
        override fun description(): String = "Add contract templates and one-time scheduling limits."
        override fun migrate(context: MigrationContext) {
            val yaml = context.yaml()
            if (!yaml.contains("limits.max-templates-per-player")) yaml["limits.max-templates-per-player"] = 32
            if (!yaml.contains("limits.max-scheduled-contracts")) yaml["limits.max-scheduled-contracts"] = 64
            if (!yaml.contains("scheduling.max-days-ahead")) yaml["scheduling.max-days-ahead"] = 30
            if (!yaml.contains("scheduling.scan-interval-seconds")) yaml["scheduling.scan-interval-seconds"] = 30
        }
    }
}
