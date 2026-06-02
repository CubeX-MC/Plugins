package org.cubexmc.config;

public interface MigrationStep {
    int fromVersion();

    int toVersion();

    String description();

    void migrate(MigrationContext context) throws Exception;
}
