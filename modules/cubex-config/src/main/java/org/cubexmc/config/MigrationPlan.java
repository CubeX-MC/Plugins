package org.cubexmc.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MigrationPlan {

    private final String name;
    private final String resourcePath;
    private String versionKey = "version";
    private int missingVersion = 1;
    private int targetVersion = 1;
    private String backupDirectory = "backups/migrations";
    private boolean restoreBackupOnSaveFailure = true;
    private MigrationFailurePolicy failurePolicy = MigrationFailurePolicy.ABORT;
    private final List<MigrationStep> steps = new ArrayList<>();

    private MigrationPlan(String name, String resourcePath) {
        this.name = name == null || name.isBlank() ? resourcePath : name;
        this.resourcePath = resourcePath;
    }

    public static MigrationPlan yaml(String name, String resourcePath) {
        return new MigrationPlan(name, resourcePath);
    }

    public MigrationPlan versionKey(String key) {
        this.versionKey = key == null || key.isBlank() ? "version" : key;
        return this;
    }

    public MigrationPlan missingVersion(int version) {
        this.missingVersion = version;
        return this;
    }

    public MigrationPlan targetVersion(int version) {
        this.targetVersion = version;
        return this;
    }

    public MigrationPlan backupDirectory(String relativePath) {
        this.backupDirectory = relativePath == null || relativePath.isBlank() ? "backups/migrations" : relativePath;
        return this;
    }

    public MigrationPlan restoreBackupOnSaveFailure(boolean enabled) {
        this.restoreBackupOnSaveFailure = enabled;
        return this;
    }

    public MigrationPlan failurePolicy(MigrationFailurePolicy policy) {
        this.failurePolicy = policy == null ? MigrationFailurePolicy.ABORT : policy;
        return this;
    }

    public MigrationPlan addStep(MigrationStep step) {
        if (step != null) {
            this.steps.add(step);
        }
        return this;
    }

    String name() {
        return name;
    }

    String resourcePath() {
        return resourcePath;
    }

    String versionKey() {
        return versionKey;
    }

    int missingVersion() {
        return missingVersion;
    }

    int targetVersion() {
        return targetVersion;
    }

    String backupDirectory() {
        return backupDirectory;
    }

    boolean restoreBackupOnSaveFailure() {
        return restoreBackupOnSaveFailure;
    }

    MigrationFailurePolicy failurePolicy() {
        return failurePolicy;
    }

    List<MigrationStep> steps() {
        return Collections.unmodifiableList(steps);
    }
}
