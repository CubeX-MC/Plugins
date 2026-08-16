package org.cubexmc.config

class NoOpMigrationStep(
    private val fromVersionValue: Int,
    private val toVersionValue: Int,
    description: String?,
) : MigrationStep {
    private val descriptionValue = description?.takeUnless { it.isBlank() } ?: "No-op migration."
    override fun fromVersion(): Int = fromVersionValue
    override fun toVersion(): Int = toVersionValue
    override fun description(): String = descriptionValue
    override fun migrate(context: MigrationContext) = Unit
}
