package org.cubexmc.config;

public final class NoOpMigrationStep implements MigrationStep {

    private final int fromVersion;
    private final int toVersion;
    private final String description;

    public NoOpMigrationStep(int fromVersion, int toVersion, String description) {
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.description = description == null || description.isBlank() ? "No-op migration." : description;
    }

    @Override
    public int fromVersion() {
        return fromVersion;
    }

    @Override
    public int toVersion() {
        return toVersion;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public void migrate(MigrationContext context) {
    }
}
