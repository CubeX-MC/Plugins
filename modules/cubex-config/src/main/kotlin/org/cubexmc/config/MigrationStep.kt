package org.cubexmc.config

interface MigrationStep {
    fun fromVersion(): Int
    fun toVersion(): Int
    fun description(): String

    @Throws(Exception::class)
    fun migrate(context: MigrationContext)
}
