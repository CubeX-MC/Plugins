package org.cubexmc.config

public interface MigrationStep {
    public fun fromVersion(): Int
    public fun toVersion(): Int
    public fun description(): String

    @Throws(Exception::class)
    public fun migrate(context: MigrationContext)
}
